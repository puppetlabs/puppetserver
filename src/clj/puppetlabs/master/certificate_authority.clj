(ns puppetlabs.master.certificate-authority
  (:import [org.joda.time DateTime]
           [org.apache.commons.io IOUtils]
           [java.io InputStream ByteArrayOutputStream ByteArrayInputStream])
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def MasterSettings
  "Paths to the various directories and files within the SSL directory,
  excluding the CA directory and its contents. These are only used during
  initialization of the master. All of these are Puppet configuration settings."
  {:requestdir       String
   :certdir          String
   :hostcert         String
   :localcacert      String
   :hostprivkey      String
   :hostpubkey       String
   :dns-alt-names    (schema/maybe String)})

(def CaSettings
  "Settings from Puppet that are necessary for CA initialization and request
  handling during normal Puppet operation.
  Most of these are Puppet configuration settings."
  {:autosign              (schema/either String Boolean)
   :allow-duplicate-certs Boolean
   :cacert                String
   :cacrl                 String
   :cakey                 String
   :capub                 String
   :ca-name               String
   :ca-ttl                schema/Int
   :cert-inventory        String
   :csrdir                String
   :load-path             [String]
   :signeddir             String
   :serial                String})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn generate-not-before-date []
  "Make the not-before date set to yesterday to avoid clock skewing issues."
  (.toDate (time/minus (time/now) (time/days 1))))

(defn generate-not-after-date []
  ;; TODO: PE-3173
  "Generate a date 5 years in the future. This will be configurable soon."
  (.toDate (time/plus (time/now) (time/years 5))))

(schema/defn settings->cadir-paths
  "Trim down the CA settings to include only paths to files and directories.
  These paths are necessary during CA initialization for determining what needs
  to be created and where they should be placed."
  [ca-settings :- CaSettings]
  (dissoc ca-settings :autosign :ca-ttl :ca-name :load-path :allow-duplicate-certs))

(schema/defn settings->master-dir-paths
  "Remove all keys from the master settings map which are not file or directory
  paths."
  [master-settings :- MasterSettings]
  (dissoc master-settings :dns-alt-names))

(defn path-to-cert
  "Return a path to the `subject`s certificate file under the `signeddir`."
  [signeddir subject]
  (str signeddir "/" subject ".pem"))

(defn path-to-cert-request
  "Return a path to the `subject`s certificate request file under the `csrdir`."
  [csrdir subject]
  (str csrdir "/" subject ".pem"))

(defn files-exist?
  "Predicate to test whether all of the files exist on disk.
  `paths` is expected to be a map with file path values,
  such as `ssldir-file-paths` or `ca-settings`."
  [paths]
  {:pre [(map? paths)]}
  (every? fs/exists? (vals paths)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Serial number functions + lock

(def serial-number-file-lock
  "The lock used to prevent concurrent access to the serial number file."
  (new Object))

(defn parse-serial-number
  "Parses a serial number from its format on disk.  See `format-serial-number`
  for the awful, gory details."
  [serial-number]
  {:post [(integer? %)]}
  (read-string (str "0x" serial-number)))

(defn get-serial-number!
  "Reads the serial number file from disk and returns the serial number."
  [serial-number-file]
  {:pre [(fs/exists? serial-number-file)]}
  (-> serial-number-file
      (slurp)
      (.trim)
      (parse-serial-number)))

(defn format-serial-number
  "Converts a serial number to the format it needs to be written in on disk.
  This function has to write serial numbers in the same format that the puppet
  ruby code does, to maintain compatibility with things like 'puppet cert';
  for whatever arcane reason, that format is 0-padding up to 4 digits."
  [serial-number]
  {:pre [(integer? serial-number)]}
  (format "%04X" serial-number))

(defn next-serial-number!
  "Returns the next serial number to be used when signing a certificate request.
  Reads the serial number as a hex value from the given file and replaces the
  contents of `serial-number-file` with the next serial number for a subsequent
  call.  Puppet's 'serial' setting defines the location of the serial number file."
  [serial-number-file]
  (locking serial-number-file-lock
    (let [serial-number (get-serial-number! serial-number-file)]
      (spit serial-number-file (format-serial-number (inc serial-number)))
      serial-number)))

(defn initialize-serial-number-file!
  "Initializes the serial number file on disk.  Serial numbers start at 1."
  [path]
  (fs/create (fs/file path))
  (spit path (format-serial-number 1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Inventory File
(defn format-date-time
  "Formats a date-time into the format expected by the ruby puppet code."
  [date-time]
  (time-format/unparse
    (time-format/formatter "YYY-MM-dd'T'HH:mm:ssz")
    (new DateTime date-time)))

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
   inventory-file]
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
        subject       (-> cert
                          (.getSubjectX500Principal)
                          (.getName))
        entry (str serial-number " " not-before " " not-after " /" subject "\n")]
    (spit inventory-file entry :append true)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Initialization

(schema/defn initialize-ca!
  "Given the CA settings, generate and write to disk all of the necessary
  SSL files for the CA. Any existing files will be replaced."
  [ca-settings :- CaSettings
   keylength :- schema/Int]
  {:post [(files-exist? (settings->cadir-paths ca-settings))]}
  (log/debug (str "Initializing SSL for the CA; settings:\n"
                  (ks/pprint-to-string ca-settings)))
  (create-parent-directories! (vals (settings->cadir-paths ca-settings)))
  (-> ca-settings :csrdir fs/file ks/mkdirs!)
  (-> ca-settings :signeddir fs/file ks/mkdirs!)
  (let [serial-number-file (:serial ca-settings)
        _ (initialize-serial-number-file! serial-number-file)
        keypair     (utils/generate-key-pair keylength)
        public-key  (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)
        x500-name   (utils/cn (:ca-name ca-settings))
        cacert      (utils/sign-certificate x500-name private-key
                                            (next-serial-number! serial-number-file)
                                            (generate-not-before-date)
                                            (generate-not-after-date)
                                            x500-name public-key)
        cacrl       (-> cacert
                        .getIssuerX500Principal
                        (utils/generate-crl private-key))]
    (write-cert-to-inventory! cacert (:cert-inventory ca-settings))
    (utils/key->pem! public-key (:capub ca-settings))
    (utils/key->pem! private-key (:cakey ca-settings))
    (utils/cert->pem! cacert (:cacert ca-settings))
    (utils/crl->pem! cacrl (:cacrl ca-settings))))

(defn split-hostnames
  "Given a comma-separated list of hostnames, return a list of the
  individual dns alt names with all surrounding whitespace removed. If
  hostnames is empty or nil, then nil is returned."
  [hostnames]
  {:pre  [(or (nil? hostnames) (string? hostnames))]
   :post [(every? string? %)]}
  (let [hostnames (str/trim (or hostnames ""))]
    (when-not (empty? hostnames)
      (map str/trim (str/split hostnames #",")))))

(schema/defn
  create-master-extensions-list
  "Create a list of extensions to be added to the master certificate."
  [settings  :- MasterSettings
   subject-name :- schema/Str]
  (let [dns-alt-names (split-hostnames (:dns-alt-names settings))
        alt-names-ext (when-not (empty? dns-alt-names)
                        ;; TODO: Create a list of OID def'ns in CA lib
                        ;;       This is happening in PE-4373
                        {:oid      "2.5.29.17"
                         :critical false
                         :value    {:dns-name (conj dns-alt-names subject-name)}})]
    (if alt-names-ext [alt-names-ext] [])))

(schema/defn initialize-master!
  "Given the SSL directory file paths, master certname, and CA information,
  generate and write to disk all of the necessary SSL files for the master.
  Any existing files will be replaced."
  [settings :- MasterSettings
   master-certname :- String
   ca-name :- String
   ca-private-key :- (schema/pred utils/private-key?)
   ca-cert :- (schema/pred utils/certificate?)
   keylength :- schema/Int
   serial-number-file :- String
   inventory-file :- String]
  {:post [(files-exist? (settings->master-dir-paths settings))]}
  (log/debug (str "Initializing SSL for the Master; settings:\n"
                  (ks/pprint-to-string settings)))
  (create-parent-directories! (vals (settings->master-dir-paths settings)))
  (-> settings :certdir fs/file ks/mkdirs!)
  (-> settings :requestdir fs/file ks/mkdirs!)
  (let [extensions   (create-master-extensions-list settings master-certname)
        keypair      (utils/generate-key-pair keylength)
        public-key   (utils/get-public-key keypair)
        private-key  (utils/get-private-key keypair)
        x500-name    (utils/cn master-certname)
        ca-x500-name (utils/cn ca-name)
        hostcert     (utils/sign-certificate ca-x500-name ca-private-key
                                             (next-serial-number! serial-number-file)
                                             (generate-not-before-date)
                                             (generate-not-after-date)
                                             x500-name public-key
                                             extensions)]
    (write-cert-to-inventory! hostcert inventory-file)
    (utils/key->pem! public-key (:hostpubkey settings))
    (utils/key->pem! private-key (:hostprivkey settings))
    (utils/cert->pem! hostcert (:hostcert settings))
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
  [glob :- String
   subject :- String]
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
  [whitelist :- String
   subject :- String
   line :- String]
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
  [whitelist :- String
   subject :- String]
  (with-open [r (io/reader whitelist)]
    (not (nil? (some (partial line-matches? whitelist subject)
                     (remove #(or (.startsWith % "#")
                                  (str/blank? %))
                             (line-seq r)))))))

(schema/defn execute-autosign-command!
  :- {:out (schema/maybe String) :err (schema/maybe String) :exit schema/Int}
  "Execute the autosign script and return a map containing the standard-out,
   standard-err, and exit code. The subject will be passed in as input, and
   the CSR stream will be provided on standard-in. The load-path will be
   prepended to the RUBYLIB found in the environment, and is intended to make
   the Puppet and Facter Ruby libraries available to the autosign script.
   All output (stdout & stderr) will be logged at the debug level."
  [executable :- String
   subject :- String
   csr-fn :- (schema/pred fn?)
   load-path :- [String]]
  (log/debugf "Executing '%s %s'" executable subject)
  (let [env     (into {} (System/getenv))
        rubylib (->> (if-let [lib (get env "RUBYLIB")]
                       (cons lib load-path)
                       load-path)
                     (map fs/absolute-path)
                     (str/join (System/getProperty "path.separator")))
        results (shell/sh executable subject
                          :in (csr-fn)
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
  "Given the configuration map from the JVM Puppet config
  service return a map with of all the CA settings."
  [{:keys [jvm-puppet jruby-puppet]}]
  (-> (select-keys jvm-puppet (keys CaSettings))
      (assoc :load-path (:load-path jruby-puppet))))

(schema/defn ^:always-validate
  config->master-settings :- MasterSettings
  "Given the configuration map from the JVM Puppet config
  service return a map with of all the master settings."
  [{:keys [jvm-puppet]}]
  (select-keys jvm-puppet (keys MasterSettings)))

(schema/defn ^:always-validate
  get-certificate :- (schema/maybe String)
  "Given a subject name and paths to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject :- String
   cacert :- String
   signeddir :- String]
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert signeddir subject))]
    (if (fs/exists? cert-path)
      (slurp cert-path))))

(schema/defn ^:always-validate
  get-certificate-request :- (schema/maybe String)
  "Given a subject name, return their certificate request as a string, or nil if
  not found.  Looks for certificate requests in `csrdir`."
  [subject :- String
   csrdir :- String]
  (let [cert-request-path (path-to-cert-request csrdir subject)]
    (if (fs/exists? cert-request-path)
      (slurp cert-request-path))))

(schema/defn ^:always-validate
  autosign-csr? :- schema/Bool
  "Return true if the CSR should be automatically signed given
  Puppet's autosign setting, and false otherwise."
  [autosign :- (schema/either String schema/Bool)
   subject :- String
   csr-fn :- (schema/pred fn?)
   load-path :- [String]]
  (if (ks/boolean? autosign)
    autosign
    (if (fs/exists? autosign)
      (if (fs/executable? autosign)
        (-> (execute-autosign-command! autosign subject csr-fn load-path)
            :exit
            zero?)
        (whitelist-matches? autosign subject))
      false)))

(schema/defn ^:always-validate
  autosign-certificate-request!
  "Given a subject name, their certificate request, and the CA settings
  from Puppet, auto-sign the request and write the certificate to disk."
  [subject :- String
   csr-fn :- (schema/pred fn?)
   {:keys [ca-name cakey signeddir ca-ttl serial cert-inventory]}]
  ;; TODO PE-3173 calculate cert expiration based on ca-ttl and the CSR
  ;;              issue date and pass to utils/sign-certificate-request
  (let [csr         (utils/pem->csr (csr-fn))
        signed-cert (utils/sign-certificate (utils/cn ca-name)
                                            (utils/pem->private-key cakey)
                                            (next-serial-number! serial)
                                            (generate-not-before-date)
                                            (generate-not-after-date)
                                            (utils/cn subject)
                                            (utils/get-public-key csr))]
    (write-cert-to-inventory! signed-cert cert-inventory)
    (utils/cert->pem! signed-cert (path-to-cert signeddir subject))))

(schema/defn ^:always-validate
  save-certificate-request!
  "Write the subject's certificate request to disk under the CSR directory."
  [subject :- String
   csr-fn :- (schema/pred fn?)
   csrdir :- String]
  (-> (utils/pem->csr (csr-fn))
      (utils/obj->pem! (path-to-cert-request csrdir subject))))

(schema/defn validate-duplicate-cert-policy!
  "Throw an exception if a allow-duplicate-certs is false
   and we already have a certificate or CSR for the subject."
  [subject :- String
   {:keys [allow-duplicate-certs csrdir signeddir]} :- CaSettings]
  (when-not allow-duplicate-certs
    ;; TODO PE-5084 In the error message below, we should say "revoked certificate"
    ;;              instead of "signed certificate" if the cert has been revoked
    (if (fs/exists? (path-to-cert signeddir subject))
      (throw
       (IllegalArgumentException.
        (str subject " already has a signed certificate; ignoring certificate request"))))
    (if (fs/exists? (path-to-cert-request csrdir subject))
      (throw
       (IllegalArgumentException.
        (str subject " already has a requested certificate; ignoring certificate request"))))))

(schema/defn ^:always-validate process-csr-submission!
  "Given a CSR for a subject (typically from the HTTP endpoint),
   perform policy checks and sign or save the CSR (based on autosign).
   Throws an exception if allow-duplicate-certs is false and there
   already exists a certificate or CSR for the subject."
  [subject :- String
   certificate-request :- InputStream
   {:keys [autosign csrdir load-path] :as settings} :- CaSettings]
  (validate-duplicate-cert-policy! subject settings)
  (with-open [byte-stream (-> certificate-request
                              input-stream->byte-array
                              ByteArrayInputStream.)]
    (let [csr-fn #(doto byte-stream .reset)]
      (if (autosign-csr? autosign subject csr-fn load-path)
        (autosign-certificate-request! subject csr-fn settings)
        (save-certificate-request! subject csr-fn csrdir)))))

(schema/defn ^:always-validate
  get-certificate-revocation-list :- String
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl :- String]
  (slurp cacrl))

(schema/defn ^:always-validate
  initialize!
  "Given the CA settings, master file paths, and the master's certname,
  prepare all necessary SSL files for the master and CA.
  If all of the necessary SSL files exist, new ones will not be generated."
  ([ca-settings master-file-paths master-certname]
    (initialize! ca-settings
                 master-file-paths
                 master-certname
                 utils/default-key-length))
  ([ca-settings       :- CaSettings
    master-settings   :- MasterSettings
    master-certname   :- String
    keylength         :- schema/Int]
    (if (files-exist? (settings->cadir-paths ca-settings))
      (log/info "CA already initialized for SSL")
      (initialize-ca! ca-settings keylength))
    (if (files-exist? (settings->master-dir-paths master-settings))
      (log/info "Master already initialized for SSL")
      (let [cakey  (-> ca-settings :cakey utils/pem->private-key)
            cacert (-> ca-settings :cacert utils/pem->cert)
            caname (:ca-name ca-settings)
            serial-number-file (:serial ca-settings)
            inventory-file (:cert-inventory ca-settings)]
        (initialize-master!
          master-settings
          master-certname
          caname
          cakey
          cacert
          keylength
          serial-number-file
          inventory-file)))))
