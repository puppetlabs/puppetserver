(ns puppetlabs.puppetserver.certificate-authority
  (:import [org.apache.commons.io IOUtils]
           [java.util Date]
           [java.io InputStream ByteArrayOutputStream ByteArrayInputStream])
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clj-time.coerce :as time-coerce]
            [slingshot.slingshot :as sling]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as utils]
            [clj-yaml.core :as yaml]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def MasterSettings
  "Settings from Puppet that are necessary for SSL initialization on the master.
   Most of these are files and directories within the SSL directory, excluding
   the CA directory and its contents; see `CaSettings` for more information.
   All of these are Puppet configuration settings."
  {:certdir        schema/Str
   :dns-alt-names  schema/Str
   :hostcert       schema/Str
   :hostprivkey    schema/Str
   :hostpubkey     schema/Str
   :localcacert    schema/Str
   :requestdir     schema/Str
   :csr-attributes schema/Str})

(def CaSettings
  "Settings from Puppet that are necessary for CA initialization
   and request handling during normal Puppet operation.
   Most of these are Puppet configuration settings."
  {:autosign              (schema/either schema/Str schema/Bool)
   :allow-duplicate-certs schema/Bool
   :cacert                schema/Str
   :cacrl                 schema/Str
   :cakey                 schema/Str
   :capub                 schema/Str
   :ca-name               schema/Str
   :ca-ttl                schema/Int
   :cert-inventory        schema/Str
   :csrdir                schema/Str
   :ruby-load-path        [schema/Str]
   :signeddir             schema/Str
   :serial                schema/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def ssl-server-cert
  "OID which indicates that a certificate can be used as an SSL server
  certificate."
  "1.3.6.1.5.5.7.3.1")

(def ssl-client-cert
  "OID which indicates that a certificate can be used as an SSL client
  certificate."
  "1.3.6.1.5.5.7.3.2")

(def puppet-oid-arc
  "The parent OID for all Puppet Labs specific X.509 certificate extensions."
  "1.3.6.1.4.1.34380.1")

(def ppRegCertExt
  "The OID for the extension with shortname 'ppRegCertExt'."
  "1.3.6.1.4.1.34380.1.1")

(def ppPrivCertExt
  "The OID for the extension with shortname 'ppPrivCertExt'."
  "1.3.6.1.4.1.34380.1.2")

(def puppet-short-names
  "A mapping of Puppet extension short names to their OIDs. These appear in
  csr_attributes.yaml."
  {:pp_uuid          "1.3.6.1.4.1.34380.1.1.1"
   :pp_instance_id   "1.3.6.1.4.1.34380.1.1.2"
   :pp_image_name    "1.3.6.1.4.1.34380.1.1.3"
   :pp_preshared_key "1.3.6.1.4.1.34380.1.1.4"})

(def netscape-comment-value
  "Standard value applied to the Netscape Comment extension for certificates"
  "Puppet Server Internal Certificate")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(schema/defn cert-validity-dates :- {:not-before Date :not-after Date}
  "Calculate the not-before & not-after dates that define a certificate's
   period of validity. The value of `ca-ttl` is expected to be in seconds,
   and the dates will be based on the current time. Returns a map in the
   form {:not-before Date :not-after Date}."
  [ca-ttl :- schema/Int]
  (let [now        (time/now)
        not-before (time/minus now (time/days 1))
        not-after  (time/plus now (time/secs ca-ttl))]
    {:not-before (.toDate not-before)
     :not-after  (.toDate not-after)}))

(schema/defn settings->cadir-paths
  "Trim down the CA settings to include only paths to files and directories.
  These paths are necessary during CA initialization for determining what needs
  to be created and where they should be placed."
  [ca-settings :- CaSettings]
  (dissoc ca-settings :autosign :ca-ttl :ca-name :ruby-load-path :allow-duplicate-certs))

(schema/defn settings->ssldir-paths
  "Remove all keys from the master settings map which are not file or directory
   paths. These paths are necessary during initialization for determining what
   needs to be created and where."
  [master-settings :- MasterSettings]
  (dissoc master-settings :dns-alt-names :csr-attributes))

(defn path-to-cert
  "Return a path to the `subject`s certificate file under the `signeddir`."
  [signeddir subject]
  (str signeddir "/" subject ".pem"))

(defn path-to-cert-request
  "Return a path to the `subject`s certificate request file under the `csrdir`."
  [csrdir subject]
  (str csrdir "/" subject ".pem"))

(defn create-parent-directories!
  "Create all intermediate directories present in each of the file paths.
  Throws an exception if the directory cannot be created."
  [paths]
  {:pre [(sequential? paths)]}
  (doseq [path paths]
    (ks/mkdirs! (fs/parent path))))

(defn input-stream->byte-array
  [input-stream]
  (with-open [os (ByteArrayOutputStream.)]
    (IOUtils/copy input-stream os)
    (.toByteArray os)))

(defn partial-state-error
  "Construct an exception appropriate for the end-user to signify that there
   are missing SSL files and the CA cannot start until action is taken."
  [found-files missing-files]
  (IllegalStateException.
   (format
    (str "Cannot initialize CA with partial state; need all files or none.\n"
         "Found:\n"
         "%s\n"
         "Missing:\n"
         "%s\n")
    (str/join "\n" found-files)
    (str/join "\n" missing-files))))

; TODO - PE-5529 - these should be moved to jvm-c-a.
(schema/defn get-subject :- String
  [cert :- (schema/pred utils/certificate?)]
  (-> cert
      (.getSubjectX500Principal)
      (.getName)))

(schema/defn get-csr-subject :- String
  [csr :- (schema/pred utils/certificate-request?)]
  (-> csr
      (.getSubject)
      (.toString)
      (utils/x500-name->CN)))

(defn contains-uppercase?
  "Does the given string contain any uppercase letters?"
  [s]
  (not= s (.toLowerCase s)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Serial number functions + lock

(def serial-file-lock
  "The lock used to prevent concurrent access to the serial number file."
  (new Object))

(schema/defn parse-serial-number :- schema/Int
  "Parses a serial number from its format on disk.  See `format-serial-number`
  for the awful, gory details."
  [serial-number :- schema/Str]
  (Integer/parseInt serial-number 16))

(schema/defn get-serial-number! :- schema/Int
  "Reads the serial number file from disk and returns the serial number."
  [serial-file :- schema/Str]
  (-> serial-file
      (slurp)
      (.trim)
      (parse-serial-number)))

(schema/defn format-serial-number :- schema/Str
  "Converts a serial number to the format it needs to be written in on disk.
  This function has to write serial numbers in the same format that the puppet
  ruby code does, to maintain compatibility with things like 'puppet cert';
  for whatever arcane reason, that format is 0-padding up to 4 digits."
  [serial-number :- schema/Int]
  (format "%04X" serial-number))

(schema/defn next-serial-number! :- schema/Int
  "Returns the next serial number to be used when signing a certificate request.
  Reads the serial number as a hex value from the given file and replaces the
  contents of `serial-file` with the next serial number for a subsequent call.
  Puppet's $serial setting defines the location of the serial number file."
  [serial-file :- schema/Str]
  (locking serial-file-lock
    (let [serial-number (get-serial-number! serial-file)]
      (spit serial-file (format-serial-number (inc serial-number)))
      serial-number)))

(schema/defn initialize-serial-file!
  "Initializes the serial number file on disk.  Serial numbers start at 1."
  [path :- schema/Str]
  (fs/create (fs/file path))
  (spit path (format-serial-number 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Inventory File

(schema/defn format-date-time :- schema/Str
  "Formats a date-time into the format expected by the ruby puppet code."
  [date-time :- Date]
  (time-format/unparse
    (time-format/formatter "YYY-MM-dd'T'HH:mm:ssz")
    (time-coerce/from-date date-time)))

(schema/defn ^:always-validate
  write-cert-to-inventory!
  "Writes an entry into Puppet's inventory file for a given certificate.
  The location of this file is defined by Puppet's 'cert_inventory' setting.
  The inventory is a text file where each line represents a certificate in the
  following format:

  $SN $NB $NA /$S

  where:
    * $SN = The serial number of the cert.  The serial number is formatted as a
            hexadecimal number, with a leading 0x, and zero-padded up to four
            digits, eg. 0x002f.
    * $NB = The 'not before' field of the cert, as a date/timestamp in UTC.
    * $NA = The 'not after' field of the cert, as a date/timestamp in UTC.
    * $S  = The distinguished name of the cert's subject."
  [cert :- (schema/pred utils/certificate?)
   inventory-file :- schema/Str]
  (let [serial-number (->> cert
                           (.getSerialNumber)
                           (format-serial-number)
                           (str "0x"))
        not-before    (-> cert
                          (.getNotBefore)
                          (format-date-time))
        not-after     (-> cert
                          (.getNotAfter)
                          (format-date-time))
        subject       (get-subject cert)
        entry (str serial-number " " not-before " " not-after " /" subject "\n")]
    (spit inventory-file entry :append true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Initialization

(schema/defn create-crl-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the CRL."
  [ca-public-key :- (schema/pred utils/public-key?)]
  [(utils/authority-key-identifier
      ca-public-key false)
   (utils/crl-number 0)])

(schema/defn create-ca-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the CA certificate."
  [ca-name :- (schema/pred utils/valid-x500-name?)
   ca-serial :- (schema/pred number?)
   ca-public-key :- (schema/pred utils/public-key?)]
  [(utils/netscape-comment
     netscape-comment-value)
   (utils/authority-key-identifier
     ca-name ca-serial false)
   (utils/basic-constraints-for-ca)
   (utils/key-usage
     #{:key-cert-sign
       :crl-sign} true)
   (utils/subject-key-identifier
     ca-public-key false)])

(schema/defn initialize-ca!
  "Given the CA settings, generate and write to disk all of the necessary
  SSL files for the CA. Any existing files will be replaced."
  [ca-settings :- CaSettings
   keylength :- schema/Int]
  (log/debug (str "Initializing SSL for the CA; settings:\n"
                  (ks/pprint-to-string ca-settings)))
  (create-parent-directories! (vals (settings->cadir-paths ca-settings)))
  (-> ca-settings :csrdir fs/file ks/mkdirs!)
  (-> ca-settings :signeddir fs/file ks/mkdirs!)
  (initialize-serial-file! (:serial ca-settings))
  (let [keypair     (utils/generate-key-pair keylength)
        public-key  (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)
        x500-name   (utils/cn (:ca-name ca-settings))
        validity    (cert-validity-dates (:ca-ttl ca-settings))
        serial      (next-serial-number! (:serial ca-settings))
        ca-exts     (create-ca-extensions x500-name
                                          serial
                                          public-key)
        cacert      (utils/sign-certificate
                      x500-name
                      private-key
                      serial
                      (:not-before validity)
                      (:not-after validity)
                      x500-name
                      public-key
                      ca-exts)
        crl-exts    (create-crl-extensions public-key)
        cacrl       (-> cacert
                        .getIssuerX500Principal
                        (utils/generate-crl private-key crl-exts))]
    (write-cert-to-inventory! cacert (:cert-inventory ca-settings))
    (utils/key->pem! public-key (:capub ca-settings))
    (utils/key->pem! private-key (:cakey ca-settings))
    (utils/cert->pem! cacert (:cacert ca-settings))
    (utils/crl->pem! cacrl (:cacrl ca-settings))))

(schema/defn split-hostnames :- (schema/maybe [schema/Str])
  "Given a comma-separated list of hostnames, return a list of the
  individual dns alt names with all surrounding whitespace removed. If
  hostnames is empty or nil, then nil is returned."
  [hostnames :- (schema/maybe schema/Str)]
  (let [hostnames (str/trim (or hostnames ""))]
    (when-not (empty? hostnames)
      (map str/trim (str/split hostnames #",")))))

(schema/defn create-dns-alt-names-ext
  "Given a hostname and a comma-separated list of DNS names, create a Subject
  Alternative Names extension."
  [host-name :- schema/Str
   alt-names :- schema/Str]
  (let [alt-names-list (split-hostnames alt-names)]
    (when-not (empty? alt-names-list)
      (utils/subject-dns-alt-names
        (conj alt-names-list host-name) false))))

(schema/defn validate-cert-subject!
  "Validate the CSR subject name.  The subject name must:
    * match the hostname specified in the HTTP request (the `subject` parameter)
    * not contain any non-printable characters or slashes
    * not contain the wildcard character (*)"
  [subject :- schema/Str
   cert-subject :- schema/Str]
  (when-not (= subject cert-subject)
    (sling/throw+
      {:type    :hostname-mismatch
       :message (str "Instance name \"" cert-subject
                     "\" does not match requested key \"" subject "\"")}))

  (when (contains-uppercase? subject)
    (sling/throw+
      {:type    :invalid-subject-name
       :message "Certificate names must be lower case."}))

  (when-not (re-matches #"\A[ -.0-~]+\Z" cert-subject)
    (sling/throw+
      {:type    :invalid-subject-name
       :message "CSR subject contains unprintable or non-ASCII characters"}))

  (when (.contains cert-subject "*")
    (sling/throw+
      {:type    :invalid-subject-name
       :message (format
                  "CSR subject contains a wildcard, which is not allowed: %s"
                  cert-subject)})))

(schema/defn allowed-extension?
  "A predicate that answers if an extension is allowed or not.
  This logic is copied out of the ruby CA."
  [extension :- (schema/pred utils/extension?)]
  (let [oid (:oid extension)]
    (or
      (utils/subtree-of? ppRegCertExt oid)
      (utils/subtree-of? ppPrivCertExt oid))))

(schema/defn validate-cert-extensions!
  "Throws an error if the extensions list contains any invalid extensions,
  according to `allowed-extension?`"
  [extensions :- (schema/pred utils/extension-list?)]
  (let [bad-extensions (remove allowed-extension? extensions)]
    (when-not (empty? bad-extensions)
      (let [bad-extension-oids (map :oid bad-extensions)]
        (sling/throw+ {:type    :disallowed-extension
                       :message (str "CSR has request extensions that are not permitted: "
                                     (str/join ", " bad-extension-oids))})))))

(schema/defn validate-dns-alt-names!
  "Validate that the provided Subject Alternative Names extension is valid for
  a cert signed by this CA. This entails:
    * Only DNS alternative names are allowed, no other types
    * Each DNS name does not contain a wildcard character (*)"
  [{:keys [oid value]} :- (schema/both (schema/pred utils/extension?))]
  (when-not (= oid "2.5.29.17")
    (sling/throw+
      {:type    :invalid-alt-name
       :message (format "An invalid OID was provided for the Subject Alt. Names extension: %s"
                        oid)}))
  (let [name-types (keys value)
        names      (:dns-name value)]
    (when-not (and (= (count name-types) 1)
                   (= (first name-types) :dns-name))
      (sling/throw+
        {:type    :invalid-alt-name
         :message "Only DNS names are allowed in the Subject Alternative Names extension"}))

    (doseq [name names]
      (when (.contains name "*")
        (sling/throw+
          {:type    :invalid-alt-name
           :message (format
                      "Cert subjectAltName contains a wildcard, which is not allowed: %s"
                      name)})))))

(schema/defn create-csr-attrs-exts
  "Parse the CSR attribtues yaml file at the given path and create a list of
  certificate extensions from the `extensions_requests` section."
  [csr-attributes-file :- schema/Str]
  (when (fs/file? csr-attributes-file)
    (try
      (let [csr-attrs (yaml/parse-string (slurp csr-attributes-file))
            ext-req (:extension_requests csr-attrs)]
        (map (fn [[oid value]]
               {:oid      (or (get puppet-short-names oid)
                              (name oid))
                :critical false
                :value    (str value)})
             ext-req))
      (catch Exception _
        (throw (IllegalStateException.
                 (str "There was a problem parsing " csr-attributes-file)))))))

(schema/defn create-master-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the master certificate."
  [master-certname :- schema/Str
   master-public-key :- (schema/pred utils/public-key?)
   ca-public-key :- (schema/pred utils/public-key?)
   {:keys [dns-alt-names csr-attributes]} :- MasterSettings]
  (let [alt-names-ext (create-dns-alt-names-ext master-certname dns-alt-names)
        csr-attr-exts (create-csr-attrs-exts csr-attributes)
        base-ext-list [(utils/netscape-comment
                         netscape-comment-value)
                       (utils/authority-key-identifier
                         ca-public-key false)
                       (utils/basic-constraints-for-non-ca true)
                       (utils/ext-key-usages
                         [ssl-server-cert ssl-client-cert] true)
                       (utils/key-usage
                         #{:key-encipherment
                           :digital-signature} true)
                       (utils/subject-key-identifier
                         master-public-key false)]]
    (validate-cert-subject! master-certname master-certname)
    (when alt-names-ext
          (validate-dns-alt-names! alt-names-ext))
    (when csr-attr-exts
          (validate-cert-extensions! csr-attr-exts))
    (filter (complement nil?)
            (concat base-ext-list [alt-names-ext] csr-attr-exts))))

(schema/defn initialize-master!
  "Given the SSL directory file paths, master certname, and CA information,
  generate and write to disk all of the necessary SSL files for the master.
  Any existing files will be replaced."
  [settings :- MasterSettings
   master-certname :- schema/Str
   ca-name :- schema/Str
   ca-private-key :- (schema/pred utils/private-key?)
   ca-public-key :- (schema/pred utils/public-key?)
   ca-cert :- (schema/pred utils/certificate?)
   keylength :- schema/Int
   serial-file :- schema/Str
   inventory-file :- schema/Str
   signeddir :- schema/Str
   ca-ttl :- schema/Int]
  (log/debug (str "Initializing SSL for the Master; settings:\n"
                  (ks/pprint-to-string settings)))
  (create-parent-directories! (vals (settings->ssldir-paths settings)))
  (-> settings :certdir fs/file ks/mkdirs!)
  (-> settings :requestdir fs/file ks/mkdirs!)
  (let [keypair      (utils/generate-key-pair keylength)
        public-key   (utils/get-public-key keypair)
        extensions   (create-master-extensions master-certname
                                               public-key
                                               ca-public-key
                                               settings)
        private-key  (utils/get-private-key keypair)
        x500-name    (utils/cn master-certname)
        ca-x500-name (utils/cn ca-name)
        validity     (cert-validity-dates ca-ttl)
        hostcert     (utils/sign-certificate ca-x500-name ca-private-key
                                             (next-serial-number! serial-file)
                                             (:not-before validity)
                                             (:not-after validity)
                                             x500-name public-key
                                             extensions)]
    (write-cert-to-inventory! hostcert inventory-file)
    (utils/key->pem! public-key (:hostpubkey settings))
    (utils/key->pem! private-key (:hostprivkey settings))
    (utils/cert->pem! hostcert (:hostcert settings))
    (utils/cert->pem! hostcert (path-to-cert signeddir master-certname))
    (utils/cert->pem! ca-cert (:localcacert settings))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Autosign

(schema/defn glob-matches? :- schema/Bool
  "Test if a subject matches the domain-name glob from the autosign whitelist.

   The glob is expected to start with a '*' and be in a form like `*.foo.bar`.
   The subject is expected to contain only lowercase characters and be in a
   form like `agent.foo.bar`. Capitalization in the glob will be ignored.

   Examples:
     (glob-matches? *.foo.bar agent.foo.bar) => true
     (glob-matches? *.baz baz) => true
     (glob-matches? *.QUX 0.1.qux) => true"
  [glob :- schema/Str
   subject :- schema/Str]
  (letfn [(munge [name]
            (-> name
                str/lower-case
                (str/split #"\.")
                reverse))
          (seq-starts-with? [a b]
            (= b (take (count b) a)))]
    (seq-starts-with? (munge subject)
                      (butlast (munge glob)))))

(schema/defn line-matches? :- schema/Bool
  "Test if the subject matches the line from the autosign whitelist.
   The line is expected to be an exact certname or a domain-name glob.
   A single line with the character '*' will match all subjects.
   If the line contains invalid characters it will be logged and
   false will be returned."
  [whitelist :- schema/Str
   subject :- schema/Str
   line :- schema/Str]
  (if (or (.contains line "#") (.contains line " "))
    (do (log/errorf "Invalid pattern '%s' found in %s" line whitelist)
        false)
    (if (= line "*")
      true
      (if (.startsWith line "*")
        (glob-matches? line subject)
        (= line subject)))))

(schema/defn whitelist-matches? :- schema/Bool
  "Test if the whitelist file contains an entry that matches the subject.
   Each line of the file is expected to contain a single entry, either as
   an exact certname or a domain-name glob, and will be evaluated verbatim.
   All blank lines and comment lines (starting with '#') will be ignored.
   If an invalid pattern is encountered, it will be logged and ignored."
  [whitelist :- schema/Str
   subject :- schema/Str]
  (with-open [r (io/reader whitelist)]
    (not (nil? (some (partial line-matches? whitelist subject)
                     (remove #(or (.startsWith % "#")
                                  (str/blank? %))
                             (line-seq r)))))))

(schema/defn execute-autosign-command!
  :- {:out (schema/maybe schema/Str) :err (schema/maybe schema/Str) :exit schema/Int}
  "Execute the autosign script and return a map containing the standard-out,
   standard-err, and exit code. The subject will be passed in as input, and
   the CSR stream will be provided on standard-in. The ruby-load-path will be
   prepended to the RUBYLIB found in the environment, and is intended to make
   the Puppet and Facter Ruby libraries available to the autosign script.
   All output (stdout & stderr) will be logged at the debug level."
  [executable :- schema/Str
   subject :- schema/Str
   csr-stream :- InputStream
   ruby-load-path :- [schema/Str]]
  (log/debugf "Executing '%s %s'" executable subject)
  (let [env     (into {} (System/getenv))
        rubylib (->> (if-let [lib (get env "RUBYLIB")]
                       (cons lib ruby-load-path)
                       ruby-load-path)
                     (map fs/absolute-path)
                     (str/join (System/getProperty "path.separator")))
        results (shell/sh executable subject
                          :in csr-stream
                          :env (merge env {:RUBYLIB rubylib}))]
    (log/debugf "Autosign command '%s %s' exit status: %d"
                executable subject (:exit results))
    (log/debugf "Autosign command '%s %s' output: %s"
                executable subject (str (:err results) (:out results)))
    results))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  config->ca-settings :- CaSettings
  "Given the configuration map from the Puppet Server config
  service return a map with of all the CA settings."
  [{:keys [puppet-server os-settings]}]
  (-> (select-keys puppet-server (keys CaSettings))
      (assoc :ruby-load-path (:ruby-load-path os-settings))))

(schema/defn ^:always-validate
  config->master-settings :- MasterSettings
  "Given the configuration map from the Puppet Server config
  service return a map with of all the master settings."
  [{:keys [puppet-server]}]
  (select-keys puppet-server (keys MasterSettings)))

(schema/defn ^:always-validate
  get-certificate :- (schema/maybe schema/Str)
  "Given a subject name and paths to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject :- schema/Str
   cacert :- schema/Str
   signeddir :- schema/Str]
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert signeddir subject))]
    (if (fs/exists? cert-path)
      (slurp cert-path))))

(schema/defn ^:always-validate
  get-certificate-request :- (schema/maybe schema/Str)
  "Given a subject name, return their certificate request as a string, or nil if
  not found.  Looks for certificate requests in `csrdir`."
  [subject :- schema/Str
   csrdir :- schema/Str]
  (let [cert-request-path (path-to-cert-request csrdir subject)]
    (if (fs/exists? cert-request-path)
      (slurp cert-request-path))))

(schema/defn ^:always-validate
  autosign-csr? :- schema/Bool
  "Return true if the CSR should be automatically signed given
  Puppet's autosign setting, and false otherwise."
  [autosign :- (schema/either schema/Str schema/Bool)
   subject :- schema/Str
   csr-stream :- InputStream
   ruby-load-path :- [schema/Str]]
  (if (ks/boolean? autosign)
    autosign
    (if (fs/exists? autosign)
      (if (fs/executable? autosign)
        (-> (execute-autosign-command! autosign subject csr-stream ruby-load-path)
            :exit
            zero?)
        (whitelist-matches? autosign subject))
      false)))

(defn create-agent-extensions
  "Given a certificate signing request, generate a list of extensions that
  should be signed onto the certificate. This includes a base set of standard
  extensions in addition to any valid extensions found on the signing request."
  [csr capub]
  {:pre [(utils/certificate-request? csr)
         (utils/public-key? capub)]
   :post [(utils/extension-list? %)]}
  (let [subj-pub-key (utils/get-public-key csr)
        csr-ext-list (utils/get-extensions csr)
        base-ext-list [(utils/netscape-comment
                         netscape-comment-value)
                       (utils/authority-key-identifier
                         capub false)
                       (utils/basic-constraints-for-non-ca true)
                       (utils/ext-key-usages
                         [ssl-server-cert ssl-client-cert] true)
                       (utils/key-usage
                         #{:key-encipherment
                           :digital-signature} true)
                       (utils/subject-key-identifier
                         subj-pub-key false)]]
    (concat base-ext-list csr-ext-list)))

(schema/defn ^:always-validate
  autosign-certificate-request!
  "Given a subject name, their certificate request, and the CA settings
  from Puppet, auto-sign the request and write the certificate to disk."
  [subject :- schema/Str
   csr :- (schema/pred utils/certificate-request?)
   {:keys [cacert capub cakey signeddir ca-ttl serial cert-inventory]} :- CaSettings]
  (let [validity    (cert-validity-dates ca-ttl)
        signed-cert (utils/sign-certificate (get-subject (utils/pem->cert cacert))
                                            (utils/pem->private-key cakey)
                                            (next-serial-number! serial)
                                            (:not-before validity)
                                            (:not-after validity)
                                            (utils/cn subject)
                                            (utils/get-public-key csr)
                                            (create-agent-extensions
                                             csr
                                             (utils/pem->public-key capub)))]
    (write-cert-to-inventory! signed-cert cert-inventory)
    (utils/cert->pem! signed-cert (path-to-cert signeddir subject))))

(schema/defn ^:always-validate
  save-certificate-request!
  "Write the subject's certificate request to disk under the CSR directory."
  [subject :- schema/Str
   csr :- (schema/pred utils/certificate-request?)
   csrdir :- schema/Str]
  (utils/obj->pem! csr (path-to-cert-request csrdir subject)))

(schema/defn validate-duplicate-cert-policy!
  "Throw a slingshot exception if allow-duplicate-certs is false
   and we already have a certificate or CSR for the subject.
   The exception map will look like:
   {:type    :duplicate-cert
    :message <specific error message>}"
  [subject :- schema/Str
   {:keys [allow-duplicate-certs csrdir signeddir]} :- CaSettings]
  ;; TODO PE-5084 In the error messages below we should say "revoked certificate"
  ;;              instead of "signed certificate" if the cert has been revoked
  (if (fs/exists? (path-to-cert signeddir subject))
    (if allow-duplicate-certs
      (log/info (str subject " already has a signed certificate; new certificate will overwrite it"))
      (sling/throw+
       {:type    :duplicate-cert
        :message (str subject " already has a signed certificate; ignoring certificate request")})))
  (if (fs/exists? (path-to-cert-request csrdir subject))
    (if allow-duplicate-certs
      (log/info (str subject " already has a requested certificate; new certificate will overwrite it"))
      (sling/throw+
       {:type    :duplicate-cert
        :message (str subject " already has a requested certificate; ignoring certificate request")}))))

(schema/defn allowed-extension?
  "A predicate that answers if an extension is allowed or not.
  This logic is copied out of the ruby CA."
  [extension :- (schema/pred utils/extension?)]
  (let [oid (:oid extension)]
    (or
      (utils/subtree-of? ppRegCertExt oid)
      (utils/subtree-of? ppPrivCertExt oid))))

(schema/defn validate-cert-extensions!
  "Throws an error if the extensions list contains any invalid extensions,
  according to `allowed-extension?`"
  [extensions :- (schema/pred utils/extension-list?)]
  (let [bad-extensions (remove allowed-extension? extensions)]
    (when-not (empty? bad-extensions)
      (let [bad-extension-oids (map :oid bad-extensions)]
        (sling/throw+ {:type    :disallowed-extension
                       :message (str "CSR has request extensions that are not permitted: "
                                     (str/join ", " bad-extension-oids))})))))

(schema/defn validate-csr-signature!
  "Throws an exception when the CSR's signature is invalid.
  See `signature-valid?` for more detail."
  [certificate-request :- (schema/pred utils/certificate-request?)]
  (when-not (utils/signature-valid? certificate-request)
    (sling/throw+
      {:type    :invalid-signature
       :message "CSR contains a public key that does not correspond to the signing key"})))

(schema/defn validate-csr!
  "Perform all policy checks against the provided certificate signing request. "
  [csr     :- (schema/pred utils/certificate-request?)
   subject :- schema/Str]
  ; These validations must happen in this order
  ; if we are to behave exactly like the ruby CA.
  (let [extensions  (utils/get-extensions csr)
        csr-subject (get-csr-subject csr)]
    (validate-cert-extensions! extensions)
    (validate-cert-subject! subject csr-subject)
    (validate-csr-signature! csr)))

(schema/defn ^:always-validate process-csr-submission!
  "Given a CSR for a subject (typically from the HTTP endpoint),
   perform policy checks and sign or save the CSR (based on autosign).
   Throws an exception if allow-duplicate-certs is false and there
   already exists a certificate or CSR for the subject."
  [subject :- schema/Str
   certificate-request :- InputStream
   {:keys [autosign csrdir ruby-load-path] :as settings} :- CaSettings]
  (validate-duplicate-cert-policy! subject settings)
  (with-open [byte-stream (-> certificate-request
                              input-stream->byte-array
                              ByteArrayInputStream.)]
    (let [csr (utils/pem->csr byte-stream)
          csr-stream (doto byte-stream .reset)]
      (if (autosign-csr? autosign subject csr-stream ruby-load-path)
        (do (validate-csr! csr subject)
            (autosign-certificate-request! subject csr settings))
        (save-certificate-request! subject csr csrdir)))))

(schema/defn ^:always-validate
  get-certificate-revocation-list :- schema/Str
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl :- schema/Str]
  (slurp cacrl))

(schema/defn ^:always-validate
  initialize!
  "Given the CA settings, master file paths, and the master's certname,
   prepare all necessary SSL files for the master and CA.
   If all of the necessary SSL files exist, new ones will not be generated.
   If only some are found (but others are missing), an exception is thrown."
  ([ca-settings master-file-paths master-certname]
    (initialize! ca-settings
                 master-file-paths
                 master-certname
                 utils/default-key-length))
  ([ca-settings :- CaSettings
    master-settings :- MasterSettings
    master-certname :- schema/Str
    keylength :- schema/Int]
    (let [required-ca-files     (vals (settings->cadir-paths ca-settings))
          required-master-files (vals (settings->ssldir-paths master-settings))]
      (if (every? fs/exists? required-ca-files)
        (log/info "CA already initialized for SSL")
        (let [{found   true
               missing false} (group-by fs/exists? required-ca-files)]
          (if (= required-ca-files missing)
            (initialize-ca! ca-settings keylength)
            (throw (partial-state-error found missing)))))
      (if (every? fs/exists? required-master-files)
        (log/info "Master already initialized for SSL")
        (initialize-master! master-settings
                            master-certname
                            (:ca-name ca-settings)
                            (utils/pem->private-key (:cakey ca-settings))
                            (utils/pem->public-key (:capub ca-settings))
                            (utils/pem->cert (:cacert ca-settings))
                            keylength
                            (:serial ca-settings)
                            (:cert-inventory ca-settings)
                            (:signeddir ca-settings)
                            (:ca-ttl ca-settings))))))
