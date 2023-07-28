(ns puppetlabs.puppetserver.certificate-authority
  (:import (java.io BufferedReader BufferedWriter FileNotFoundException InputStream ByteArrayOutputStream ByteArrayInputStream File Reader StringReader IOException)
           (java.nio CharBuffer)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute PosixFilePermissions)
           (java.security PrivateKey PublicKey)
           (java.security.cert X509Certificate CRLException CertPathValidatorException X509CRL)
           (java.text SimpleDateFormat)
           (java.util Date)
           (java.util.concurrent.locks ReentrantReadWriteLock ReentrantLock)
           (org.apache.commons.io IOUtils)
           (org.bouncycastle.pkcs PKCS10CertificationRequest)
           (org.joda.time DateTime))
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clj-time.coerce :as time-coerce]
            [slingshot.slingshot :as sling]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.kitchensink.file :as ks-file]
            [puppetlabs.puppetserver.common :as common]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.ssl-utils.core :as utils]
            [puppetlabs.puppetserver.shell-utils :as shell-utils]
            [puppetlabs.i18n.core :as i18n]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public utilities

;; Pattern is one or more digit followed by time unit
(def digits-with-unit-pattern #"(\d+)(y|d|h|m|s)")
(def repeated-digits-with-unit-pattern #"((\d+)(y|d|h|m|s))+")

(defn duration-string?
  "Returns true if string is formatted with duration string pairs only, otherwise returns nil.
   Ignores whitespace."
  [maybe-duration-string]
  (when (string? maybe-duration-string)
    (let [no-whitespace-string (clojure.string/replace maybe-duration-string #" " "")]
      (some? (re-matches repeated-digits-with-unit-pattern no-whitespace-string)))))

(defn duration-str->sec
  "Converts a string containing any combination of duration string pairs in the format '<num>y' '<num>d' '<num>m' '<num>h' '<num>s'
   to a total number of seconds.
   nil is returned if the input is not a string or not a string containing any valid duration string pairs."
  [string-input]
  (when (duration-string? string-input)
    (let [pattern-matcher (re-matcher digits-with-unit-pattern string-input)
          first-match (re-find pattern-matcher)]
      (loop [[_match-str digits unit] first-match
             running-total 0]
        (let [unit-in-seconds (case unit
                                "y" 31536000 ;; 365 day year, not a real year
                                "d" 86400
                                "h" 3600
                                "m" 60
                                "s" 1)
              total-seconds (+ running-total (* (Integer/parseInt digits) unit-in-seconds))
              next-match (re-find pattern-matcher)]
          (if (some? next-match)
            (recur next-match total-seconds)
            total-seconds))))))

(defn get-ca-ttl
  "Returns ca-ttl value as an integer. If a value is set in certificate-authority that value is returned.
   Otherwise puppet config setting is returned"
  [puppetserver certificate-authority]
  (let [ca-config-value (duration-str->sec (:ca-ttl certificate-authority))
        puppet-config-value (:ca-ttl puppetserver)]
    (when (and ca-config-value puppet-config-value)
        (log/warn (i18n/trs "Detected ca-ttl setting in CA config which will take precedence over puppet.conf setting")))
    (or ca-config-value puppet-config-value)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def AutoSignInput
  (schema/cond-pre schema/Bool schema/Str))

(def CertificateOrCSR
  (schema/cond-pre X509Certificate PKCS10CertificationRequest))

(def TTLDuration
  (schema/cond-pre schema/Int schema/Str))

(def MasterSettings
  "Settings from Puppet that are necessary for SSL initialization on the master.
   Most of these are files and directories within the SSL directory, excluding
   the CA directory and its contents; see `CaSettings` for more information.
   All of these are Puppet configuration settings."
  {:certdir        schema/Str
   :dns-alt-names  schema/Str
   :hostcert       schema/Str
   :hostcrl        schema/Str
   :hostprivkey    schema/Str
   :hostpubkey     schema/Str
   :keylength      schema/Int
   :localcacert    schema/Str
   :privatekeydir  schema/Str
   :requestdir     schema/Str
   :csr-attributes schema/Str})

(def AccessControl
  "Defines which clients are allowed access to the various CA endpoints.
   Each endpoint has a sub-section containing the client whitelist.
   Currently we only control access to the certificate_status(es) endpoints."
  {(schema/optional-key :certificate-status) ringutils/WhitelistSettings})

(defn positive-integer?
  [i]
  (and (integer? i)
       (pos? i)))

(def PosInt
  "Any integer z in Z where z > 0."
  (schema/pred positive-integer? 'positive-integer?))

(def CaSettings
  "Settings from Puppet that are necessary for CA initialization
   and request handling during normal Puppet operation.
   Most of these are Puppet configuration settings."
  {:access-control                   (schema/maybe AccessControl)
   :allow-authorization-extensions   schema/Bool
   :allow-duplicate-certs            schema/Bool
   :allow-subject-alt-names          schema/Bool
   :allow-auto-renewal               schema/Bool
   :auto-renewal-cert-ttl            TTLDuration
   :allow-header-cert-info           schema/Bool
   :autosign                         AutoSignInput
   :cacert                           schema/Str
   :cadir                            schema/Str
   :cacrl                            schema/Str
   :cakey                            schema/Str
   :capub                            schema/Str
   :ca-name                          schema/Str
   :ca-ttl                           schema/Int
   :cert-inventory                   schema/Str
   :csrdir                           schema/Str
   :keylength                        schema/Int
   :manage-internal-file-permissions schema/Bool
   :ruby-load-path                   [schema/Str]
   :gem-path                         schema/Str
   :signeddir                        schema/Str
   :serial                           schema/Str
   ;; Path to file containing list of infra node certificates including MoM
   ;; provisioned by PE or user in case of FOSS
   :infra-nodes-path                 schema/Str
   ;; Path to file containing serial numbers of infra node certificates
   ;; This would be re-generated anytime the infra-nodes list is updated.
   :infra-node-serials-path          schema/Str
   ;; Path to Infrastructure CRL file containing infra certificates
   :infra-crl-path                   schema/Str
   ;; Option to continue using full CRL instead of infra CRL if desired
   ;; Infra CRL would be enabled by default.
   :enable-infra-crl                 schema/Bool
   :serial-lock                      ReentrantReadWriteLock
   :serial-lock-timeout-seconds      PosInt
   :crl-lock                         ReentrantReadWriteLock
   :crl-lock-timeout-seconds         PosInt
   :inventory-lock                   ReentrantLock
   :inventory-lock-timeout-seconds   PosInt})

(def DesiredCertificateState
  "The pair of states that may be submitted to the certificate
   status endpoint for signing and revoking certificates."
  (schema/enum :signed :revoked))

(def CertificateState
  "The list of states a certificate may be in."
  (schema/enum "requested" "signed" "revoked"))

(def CertificateDetails
  "CA and client certificate details; notAfter, notBefore and serial values."
  {:not_after     schema/Str
   :not_before    schema/Str
   :serial_number schema/Int})

(def CertificateStatusResult
  "Various information about the state of a certificate or
   certificate request that is provided by the certificate
   status endpoint."
  (let [base {:name              schema/Str
              :state             CertificateState
              :dns_alt_names     [schema/Str]
              :subject_alt_names [schema/Str]
              :authorization_extensions {schema/Str schema/Str}
              :fingerprint       schema/Str
              :fingerprints      {schema/Keyword schema/Str}}]
    ;; The map should either have all of the CertificateDetails keys or none of
    ;; them
    (schema/conditional
      :serial_number (merge
                       base
                       CertificateDetails)
      :else          base)))

(def Certificate
  (schema/pred utils/certificate?))

(def CertificateRequest
  (schema/pred utils/certificate-request?))

(def Extension
  (schema/pred utils/extension?))

(def KeyIdExtension
  {:key-identifier [Byte]
   schema/Keyword schema/Any})

(def CertificateRevocationList
  (schema/pred utils/certificate-revocation-list?))

(def OutcomeInfo
  "Generic map of outcome & message for API consumers"
  {:outcome (schema/enum :success :not-found :error)
   :message schema/Str})

(def OIDMappings
  {schema/Str schema/Keyword})

(def default-allow-subj-alt-names
  false)

(def default-allow-auth-extensions
  false)

(def default-serial-lock-timeout-seconds
  5)

(def default-crl-lock-timeout-seconds
  ;; for large crls, and a slow disk a longer timeout is needed
  60)

(def default-inventory-lock-timeout-seconds
  60)

(def default-auto-ttl-renewal
  "90d") ; 90 days by default

(def default-auto-ttl-renewal-seconds
  (duration-str->sec default-auto-ttl-renewal)) ; 90 days by default

(schema/defn ^:always-validate initialize-ca-config
  "Adds in default ca config keys/values, which may be overwritten if a value for
  any of those keys already exists in the ca-data"
  [ca-data]
  (let [cadir (:cadir ca-data)
        defaults {:infra-nodes-path (str cadir "/infra_inventory.txt")
                  :infra-node-serials-path (str cadir "/infra_serials")
                  :infra-crl-path (str cadir "/infra_crl.pem")
                  :enable-infra-crl false
                  :allow-subject-alt-names default-allow-subj-alt-names
                  :allow-authorization-extensions default-allow-auth-extensions
                  :serial-lock-timeout-seconds default-serial-lock-timeout-seconds
                  :crl-lock-timeout-seconds default-crl-lock-timeout-seconds
                  :inventory-lock-timeout-seconds default-inventory-lock-timeout-seconds
                  :allow-auto-renewal false
                  :auto-renewal-cert-ttl default-auto-ttl-renewal
                  :allow-header-cert-info false}]
    (merge defaults ca-data)))

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

;; Note: When updating the following OIDs make sure to also update the OIDs here:
;; https://github.com/puppetlabs/puppet/blob/master/lib/puppet/ssl/oids.rb#L29-L67

(def puppet-oid-arc
  "The parent OID for all Puppet Labs specific X.509 certificate extensions."
  "1.3.6.1.4.1.34380.1")

(def ppRegCertExt
  "The OID for the extension with shortname 'ppRegCertExt'."
  "1.3.6.1.4.1.34380.1.1")

(def ppPrivCertExt
  "The OID for the extension with shortname 'ppPrivCertExt'."
  "1.3.6.1.4.1.34380.1.2")

(def ppAuthCertExt
  "The OID for the extension with shortname 'ppAuthCertExt'."
  "1.3.6.1.4.1.34380.1.3")

(def subject-alt-names-oid
  "2.5.29.17")

;; Extension that is checked when allowing access to the certificate_status(es)
;; endpoint. Should only be present on the puppet master cert.
(def cli-auth-oid
  "1.3.6.1.4.1.34380.1.3.39")

(def puppet-short-names
  "A mapping of Puppet extension short names to their OIDs. These appear in
  csr_attributes.yaml."
  {:pp_uuid             "1.3.6.1.4.1.34380.1.1.1"
   :pp_instance_id      "1.3.6.1.4.1.34380.1.1.2"
   :pp_image_name       "1.3.6.1.4.1.34380.1.1.3"
   :pp_preshared_key    "1.3.6.1.4.1.34380.1.1.4"
   :pp_cost_center      "1.3.6.1.4.1.34380.1.1.5"
   :pp_product          "1.3.6.1.4.1.34380.1.1.6"
   :pp_project          "1.3.6.1.4.1.34380.1.1.7"
   :pp_application      "1.3.6.1.4.1.34380.1.1.8"
   :pp_service          "1.3.6.1.4.1.34380.1.1.9"
   :pp_employee         "1.3.6.1.4.1.34380.1.1.10"
   :pp_created_by       "1.3.6.1.4.1.34380.1.1.11"
   :pp_environment      "1.3.6.1.4.1.34380.1.1.12"
   :pp_role             "1.3.6.1.4.1.34380.1.1.13"
   :pp_software_version "1.3.6.1.4.1.34380.1.1.14"
   :pp_department       "1.3.6.1.4.1.34380.1.1.15"
   :pp_cluster          "1.3.6.1.4.1.34380.1.1.16"
   :pp_provisioner      "1.3.6.1.4.1.34380.1.1.17"
   :pp_region           "1.3.6.1.4.1.34380.1.1.18"
   :pp_datacenter       "1.3.6.1.4.1.34380.1.1.19"
   :pp_zone             "1.3.6.1.4.1.34380.1.1.20"
   :pp_network          "1.3.6.1.4.1.34380.1.1.21"
   :pp_securitypolicy   "1.3.6.1.4.1.34380.1.1.22"
   :pp_cloudplatform    "1.3.6.1.4.1.34380.1.1.23"
   :pp_apptier          "1.3.6.1.4.1.34380.1.1.24"
   :pp_hostname         "1.3.6.1.4.1.34380.1.1.25"
   :pp_owner            "1.3.6.1.4.1.34380.1.1.26"
   :pp_authorization    "1.3.6.1.4.1.34380.1.3.1"
   :pp_auth_role        "1.3.6.1.4.1.34380.1.3.13"
   :pp_cli_auth         cli-auth-oid})

;; OID for the attribute that indicates if the agent supports auto-renewal or not
(def pp_auth_auto_renew-attribute
  "1.3.6.1.4.1.34380.1.3.2",)

(def netscape-comment-value
  "Standard value applied to the Netscape Comment extension for certificates"
  "Puppet Server Internal Certificate")

(defn required-ca-files
  "The set of SSL related files that are required on the CA."
  [enable-infra-crl]
  (set/union #{:cacert :cacrl :cakey :cert-inventory :serial}
     (if enable-infra-crl #{:infra-nodes-path :infra-crl-path} #{})))

(def max-ca-ttl
  "The longest valid duration for CA certs, in seconds. 50 standard years."
  1576800000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(def private-key-perms
  "Posix permissions for all private keys on disk."
  "rw-r-----")

(def public-key-perms
  "Posix permissions for all public keys on disk."
  "rw-r--r--")

(def private-key-dir-perms
  "Posix permissions for the private key directory on disk."
  "rwxr-x---")

(schema/defn create-file-with-perms :- File
  "Create a new empty file which has the provided posix file permissions. The
  permissions string is in the form of the standard 9 character posix format. "
  [path :- schema/Str
   permissions :- schema/Str]
  (let [perms-set (PosixFilePermissions/fromString permissions)]
    (-> (ks-file/str->path path)
        (Files/createFile
         (into-array FileAttribute
                     [(PosixFilePermissions/asFileAttribute perms-set)]))
        (Files/setPosixFilePermissions perms-set)
        (.toFile))))

(schema/defn cert-validity-dates :- {:not-before Date :not-after Date}
  "Calculate the not-before & not-after dates that define a certificate's
   period of validity. The value of `ca-ttl` is expected to be in seconds,
   and the dates will be based on the current time. Returns a map in the
   form {:not-before Date :not-after Date}."
  [ca-ttl :- schema/Int]
  (let [now        (time/now)
        not-before (time/minus now (time/days 1))
        not-after  (time/plus now (time/seconds ca-ttl))]
    {:not-before (.toDate not-before)
     :not-after  (.toDate not-after)}))


(schema/defn settings->cadir-paths
  "Trim down the CA settings to include only paths to files and directories.
  These paths are necessary during CA initialization for determining what needs
  to be created and where they should be placed."
  [ca-settings :- CaSettings]
  (let [settings' (dissoc ca-settings
                    :access-control
                    :allow-authorization-extensions
                    :allow-duplicate-certs
                    :allow-subject-alt-names
                    :autosign
                    :ca-name
                    :ca-ttl
                    :allow-header-cert-info
                    :keylength
                    :manage-internal-file-permissions
                    :ruby-load-path
                    :gem-path
                    :enable-infra-crl
                    :serial-lock-timeout-seconds
                    :serial-lock
                    :crl-lock-timeout-seconds
                    :crl-lock
                    :inventory-lock
                    :inventory-lock-timeout-seconds
                    :allow-auto-renewal
                    :auto-renewal-cert-ttl)]
    (if (:enable-infra-crl ca-settings)
      settings'
      (dissoc settings' :infra-crl-path :infra-node-serials-path))))

(schema/defn settings->ssldir-paths
  "Remove all keys from the master settings map which are not file or directory
   paths. These paths are necessary during initialization for determining what
   needs to be created and where."
  [master-settings :- MasterSettings]
  (dissoc master-settings :dns-alt-names :csr-attributes :keylength))

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

(schema/defn partial-state-error :- Exception
  "Construct an exception appropriate for the end-user to signify that there
   are missing SSL files and the master or CA cannot start until action is taken."
  [master-or-ca :- schema/Str
   found-files :- [schema/Str]
   missing-files :- [schema/Str]]
  (IllegalStateException.
   (format "%s\n%s\n%s\n%s\n%s\n"
           (i18n/trs "Cannot initialize {0} with partial state; need all files or none." master-or-ca)
           (i18n/trs "Found:")
           (str/join "\n" found-files)
           (i18n/trs "Missing:")
           (str/join "\n" missing-files))))

; TODO - PE-5529 - this should be moved to jvm-c-a.
(schema/defn get-csr-subject :- schema/Str
  [csr :- CertificateRequest]
  (-> csr
      (.getSubject)
      (.toString)
      (utils/x500-name->CN)))

(defn contains-uppercase?
  "Does the given string contain any uppercase letters?"
  [s]
  (not= s (.toLowerCase s)))

(schema/defn dns-alt-names :- [schema/Str]
  "Get the list of DNS alt names on the provided certificate or CSR.
   Each name will be prepended with 'DNS:'."
  [cert-or-csr :- CertificateOrCSR]
  (mapv (partial str "DNS:")
        (utils/get-subject-dns-alt-names cert-or-csr)))

(schema/defn subject-alt-names :- [schema/Str]
  "Get the list of both DNS and IP alt names on the provided certificate or CSR.
   Each name will be prepended with 'DNS:' or 'IP:'."
  [cert-or-csr :- CertificateOrCSR]
  (into (mapv (partial str "IP:") (utils/get-subject-ip-alt-names cert-or-csr))
        (mapv (partial str "DNS:") (utils/get-subject-dns-alt-names cert-or-csr))))

(schema/defn get-csr-attributes :- utils/SSLMultiValueAttributeList
  [csr :- PKCS10CertificationRequest]
  (utils/get-attributes csr))

(schema/defn authorization-extensions :- {schema/Str schema/Str}
  "Get the authorization extensions for the certificate or CSR.
  These are extensions that fall under the ppAuthCert OID arc.
  Returns a map of OIDS to values."
  [cert-or-csr :- CertificateOrCSR]
  (let [extensions (utils/get-extensions cert-or-csr)
        auth-exts (filter (fn [{:keys [oid]}]
                            (utils/subtree-of? ppAuthCertExt oid))
                          extensions)]
    (reduce (fn [exts ext]
             ;; Get the short name from the OID if available
             (let [short-name (get (set/map-invert puppet-short-names) (:oid ext))
                   short-name-string (when short-name (name short-name))
                   oid (or short-name-string (:oid ext))
                   value (:value ext)]
               (assoc exts oid value)))
            {}
            auth-exts)))

(defn seq-contains? [coll target] (some #(= target %) coll))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Writing various SSL objects safely
;; These versions all encode writing atomically and knowledge of file permissions

(schema/defn write-public-key
  "Encode a key to PEM format and write it to a file atomically and with
  appropriate permissions for a public key."
  [key :- PublicKey
   path :- schema/Str]
  (ks-file/atomic-write path (partial utils/key->pem! key) public-key-perms))

(schema/defn write-private-key
  "Encode a key to PEM format and write it to a file atomically and with
  appropriate permissions for a private key."
  [key :- PrivateKey
   path :- schema/Str]
  (ks-file/atomic-write path (partial utils/key->pem! key) private-key-perms))

(schema/defn write-cert
  "Encode a certificate to PEM format and write it to a file atomically and with
  appropriate permissions."
  [cert :- Certificate
   path :- schema/Str]
  (ks-file/atomic-write path (partial utils/cert->pem! cert) public-key-perms))

(schema/defn write-crl
  "Encode a CRL to PEM format and write it to a file atomically and with
  appropriate permissions."
  [crl :- CertificateRevocationList
   path :- schema/Str]
  (ks-file/atomic-write path (partial utils/crl->pem! crl) public-key-perms))

(schema/defn write-crls
  "Encode a list of CRLS to PEM format and write it to a file atomically and
  with appropriate permissions.  Note, assumes proper locking is done."
  [crls :- [CertificateRevocationList]
   path :- schema/Str]
  ;; use an atomic write for crash safety.
  (ks-file/atomic-write path (partial utils/objs->pem! crls) public-key-perms))

(schema/defn write-csr
  "Encode a CSR to PEM format and write it to a file atomically and with
  appropriate permissions."
  [csr :- CertificateRequest
   path :- schema/Str]
  (ks-file/atomic-write path (partial utils/obj->pem! csr) public-key-perms))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Serial number functions

(def serial-lock-descriptor
  "Text used in exceptions to help identify locking issues"
  "serial-file")

(def crl-lock-descriptor
  "Text used in exceptions to help identify locking issues"
  "crl-file")

(def inventory-lock-descriptor
  "Text used in exceptions to help identify locking issues"
  "inventory-file")

(schema/defn parse-serial-number :- schema/Int
  "Parses a serial number from its format on disk.  See `format-serial-number`
  for the awful, gory details."
  [serial-number :- schema/Str]
  (Integer/parseInt serial-number 16))

(schema/defn get-serial-number! :- schema/Int
  "Reads the serial number file from disk and returns the serial number."
  [{:keys [serial serial-lock serial-lock-timeout-seconds]} :- CaSettings]
  (common/with-safe-read-lock serial-lock serial-lock-descriptor serial-lock-timeout-seconds
    (-> serial
        (slurp)
        (.trim)
        (parse-serial-number))))

(schema/defn format-serial-number :- schema/Str
  "Converts a serial number to the format it needs to be written in on disk.
  This function has to write serial numbers in the same format that the puppet
  ruby code does, to maintain compatibility with things like 'puppet cert';
  for whatever arcane reason, that format is 0-padding up to 4 digits."
  [serial-number :- schema/Int]
  (format "%04X" serial-number))

(def serial-file-permissions
  "rw-r--r--")

(schema/defn next-serial-number! :- schema/Int
  "Returns the next serial number to be used when signing a certificate request.
  Reads the serial number as a hex value from the given file and replaces the
  contents of `serial-file` with the next serial number for a subsequent call.
  Puppet's $serial setting defines the location of the serial number file."
  [{:keys [serial serial-lock serial-lock-timeout-seconds] :as ca-settings} :- CaSettings]
  (common/with-safe-write-lock serial-lock serial-lock-descriptor serial-lock-timeout-seconds
    (let [serial-number (get-serial-number! ca-settings)]
      (ks-file/atomic-write-string serial
                                   (format-serial-number (inc serial-number))
                                   serial-file-permissions)
      serial-number)))

(schema/defn initialize-serial-file!
  "Initializes the serial number file on disk.  Serial numbers start at 1."
  [{:keys [serial serial-lock serial-lock-timeout-seconds]} :- CaSettings]
  (common/with-safe-write-lock serial-lock serial-lock-descriptor serial-lock-timeout-seconds
    (ks-file/atomic-write-string serial
                                 (format-serial-number 1)
                                 serial-file-permissions)))

(schema/defn write-local-cacrl! :- (schema/maybe Exception)
  "Spits the contents of 'cacrl-contents' string to the 'localcacrl' file
  location if the 'cacrl' string contains valid CRL pem data. On success, return
  nil. On failure, return the Exception captured from the failed attempt to
  parse the CRL pem data."
  [localcacrl-path :- schema/Str
   cacrl-contents :- schema/Str]
  (let [crl-reader (StringReader. cacrl-contents)]
    (try
      (when (zero? (count (utils/pem->crls crl-reader)))
        (throw (CRLException. "No CRL data found")))
      (ks-file/atomic-write-string localcacrl-path
                                   cacrl-contents
                                   public-key-perms)
      nil
      (catch IOException e
        e)
      (catch CRLException e
        e))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Inventory File

(schema/defn format-date-time :- schema/Str
  "Formats a date-time into the format expected by the ruby puppet code."
  [date-time :- Date]
  (time-format/unparse
    (time-format/formatter "YYY-MM-dd'T'HH:mm:ssz")
    (time-coerce/from-date date-time)))

(def buffer-copy-size (* 64 1024))

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
  [cert :- Certificate
   {:keys [cert-inventory inventory-lock inventory-lock-timeout-seconds]} :- CaSettings]
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
        subject       (utils/get-subject-from-x509-certificate cert)
        entry (str serial-number " " not-before " " not-after " /" subject "\n")
        stream-content-fn (fn [^BufferedWriter writer]
                            (log/trace (i18n/trs "Begin append to inventory file."))
                            (let [copy-buffer (CharBuffer/allocate buffer-copy-size)]
                              (try
                                (with-open [^BufferedReader reader (io/reader cert-inventory)]
                                  ;; copy all the existing content
                                  (loop [read-length (.read reader copy-buffer)]
                                    (when (< 0 read-length)
                                        (when (pos? read-length)
                                          (.write writer (.array copy-buffer) 0 read-length))
                                        (.clear copy-buffer)
                                        (recur (.read reader copy-buffer)))))
                                (catch FileNotFoundException _e
                                  (log/trace (i18n/trs "Inventory file not found.  Assume empty.")))
                                (catch Throwable e
                                  (log/error e (i18n/trs "Error while appending to inventory file."))
                                  (throw e))))
                            (.write writer entry)
                            (.flush writer)
                            (log/trace (i18n/trs "Finish append to inventory file. ")))]
    (common/with-safe-lock inventory-lock inventory-lock-descriptor inventory-lock-timeout-seconds
      (log/debug (i18n/trs "Append \"{1}\" to inventory file {0}" cert-inventory entry))
      (ks-file/atomic-write cert-inventory stream-content-fn))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Initialization

(schema/defn validate-settings!
  "Ensure config values are valid for basic CA behaviors."
  [settings :- CaSettings]
  (let [ca-ttl (:ca-ttl settings)
        certificate-status-access-control (get-in settings
                                                  [:access-control
                                                   :certificate-status])
        certificate-status-whitelist (:client-whitelist
                                      certificate-status-access-control)]
    (when (> ca-ttl max-ca-ttl)
      (throw (IllegalStateException.
              (i18n/trs "Config setting ca_ttl must have a value below {0}" max-ca-ttl))))
    (cond
      (or (false? (:authorization-required certificate-status-access-control))
          (not-empty certificate-status-whitelist))
      (log/warn (format "%s %s"
                        (i18n/trs "The ''client-whitelist'' and ''authorization-required'' settings in the ''certificate-authority.certificate-status'' section are deprecated and will be removed in a future release.")
                        (i18n/trs "Remove these settings and create an appropriate authorization rule in the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")))
      (not (nil? certificate-status-whitelist))
      (log/warn (format "%s %s %s"
                        (i18n/trs "The ''client-whitelist'' and ''authorization-required'' settings in the ''certificate-authority.certificate-status'' section are deprecated and will be removed in a future release.")
                        (i18n/trs "Because the ''client-whitelist'' is empty and ''authorization-required'' is set to ''false'', the ''certificate-authority.certificate-status'' settings will be ignored and authorization for the ''certificate_status'' endpoints will be done per the authorization rules in the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")
                        (i18n/trs "To suppress this warning, remove the ''certificate-authority'' configuration settings."))))))

(schema/defn ensure-cn-as-san :- utils/SSLExtension
  "Given the SSLExtension for subject alt names and a common name, ensure that the CN is listed in the SAN dns name list."
  [extension :- utils/SSLExtension
   cn :- schema/Str]
  (if (some #(= cn %) (get-in extension [:value :dns-name]))
    extension
    (update-in extension [:value :dns-name] conj cn)))

(schema/defn is-san?
  [extension]
  (= utils/subject-alt-name-oid (:oid extension)))

(schema/defn ensure-ext-list-has-cn-san
  "Given a list of extensions to be signed onto a certificate, ensure that a CN is provided
   as a subject alternative name; if no subject alternative name extension is found, generate a new
   extension and add it to the list with the CN supplied"
  [cn :- schema/Str
   extensions :- (schema/pred utils/extension-list?)]
  (let [[san] (filter is-san? extensions)
        sans-san (filter (complement is-san?) extensions)
        new-san (if san
                  (ensure-cn-as-san san cn)
                  (utils/subject-dns-alt-names [cn] false))]
    (conj sans-san new-san)))

(schema/defn create-ca-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the CA certificate."
  [issuer-public-key :- (schema/pred utils/public-key?)
   ca-public-key :- (schema/pred utils/public-key?)]
  (vec
    (cons (utils/netscape-comment
           netscape-comment-value)
          (utils/create-ca-extensions issuer-public-key ca-public-key))))

(schema/defn read-infra-nodes
    "Returns a list of infra nodes or infra node serials from the specified file organized as one item per line."
    [infra-file-reader :- Reader]
    (line-seq infra-file-reader))

(defn- write-infra-serials-to-writer
  [writer infra-nodes-path signeddir]
  (try
    (with-open [infra-nodes-reader (io/reader infra-nodes-path)]
      (let [infra-nodes (read-infra-nodes infra-nodes-reader)]
        (doseq [infra-node infra-nodes]
          (try
            (let [infra-serial (-> (path-to-cert signeddir infra-node)
                                   (utils/pem->cert)
                                   (utils/get-serial))]
              (.write writer (str infra-serial))
              (.newLine writer))
            (catch FileNotFoundException _
              (log/warn
               (i18n/trs
                (str
                 "Failed to find/load certificate for Puppet Infrastructure Node:"
                 infra-node))))))))
    (catch FileNotFoundException _
      (log/warn (i18n/trs (str infra-nodes-path " does not exist"))))))

(schema/defn generate-infra-serials
  "Given a list of infra nodes it will create a file containing
   serial numbers of their certificates (listed on separate lines).
   It is expected have at least one entry (MoM)"
  [{:keys [infra-nodes-path infra-node-serials-path signeddir]} :- CaSettings]
  (ks-file/atomic-write infra-node-serials-path
                        #(write-infra-serials-to-writer %
                                                        infra-nodes-path
                                                        signeddir)
                        public-key-perms))

(defn symlink-cadir
  "Symlinks the new cadir that ends in 'puppetserver/ca' to the old cadir
  of 'puppet/ssl/ca' for backwards compatibility. Will delete the old cadir
  if it exists. Does nothing if set to a custom value."
  [cadir]
  (let [[_ base] (re-matches #"(.*)puppetserver/ca" cadir)
        old-cadir (str base "puppet/ssl/ca")]
    (when base
      (when (fs/exists? old-cadir)
        (fs/delete-dir old-cadir))
      (fs/sym-link old-cadir cadir)
      ;; Ensure the symlink has the same ownership as the actual cadir.
      ;; Symlink permissions are ignored in favor of the target's permissions,
      ;; so we don't have to change those.
      (let [old-cadir-path (ks-file/str->path old-cadir)
            cadir-path (ks-file/str->path cadir)
            owner (Files/getOwner cadir-path ks-file/nofollow-links)
            group (Files/getAttribute cadir-path "posix:group" ks-file/nofollow-links)]
        (Files/setOwner old-cadir-path owner)
        (Files/setAttribute old-cadir-path "posix:group" group ks-file/nofollow-links)))))

(schema/defn generate-ssl-files!
  "Given the CA settings, generate and write to disk all of the necessary
  SSL files for the CA. Any existing files will be replaced."
  [ca-settings :- CaSettings]
  (log/debug (str (i18n/trs "Initializing SSL for the CA; settings:")
                  "\n"
                  (ks/pprint-to-string ca-settings)))
  (create-parent-directories! (vals (settings->cadir-paths ca-settings)))
  (-> ca-settings :csrdir fs/file ks/mkdirs!)
  (-> ca-settings :signeddir fs/file ks/mkdirs!)
  (initialize-serial-file! ca-settings)
  (-> ca-settings :infra-nodes-path fs/file fs/create)
  (generate-infra-serials ca-settings)
  (let [keypair     (utils/generate-key-pair (:keylength ca-settings))
        public-key  (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)
        x500-name   (utils/cn (:ca-name ca-settings))
        validity    (cert-validity-dates (:ca-ttl ca-settings))
        serial      (next-serial-number! ca-settings)
        ;; Since this is a self-signed cert, the issuer key and the
        ;; key for this cert are the same
        ca-exts     (create-ca-extensions public-key
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
        cacrl       (utils/generate-crl (.getIssuerX500Principal cacert)
                                        private-key
                                        public-key)
        infra-crl (utils/generate-crl (.getIssuerX500Principal cacert)
                                      private-key
                                      public-key)]
    (write-cert-to-inventory! cacert ca-settings)
    (write-public-key public-key (:capub ca-settings))
    (write-private-key private-key (:cakey ca-settings))
    (write-cert cacert (:cacert ca-settings))
    (write-crl cacrl (:cacrl ca-settings))
    (write-crl infra-crl (:infra-crl-path ca-settings))
    (symlink-cadir (:cadir ca-settings))))

(schema/defn split-hostnames :- (schema/maybe [schema/Str])
  "Given a comma-separated list of hostnames, return a list of the
  individual dns alt names with all surrounding whitespace removed. If
  hostnames is empty or nil, then nil is returned."
  [hostnames :- (schema/maybe schema/Str)]
  (let [hostnames (str/trim (or hostnames ""))]
    (when-not (empty? hostnames)
      (map str/trim (str/split hostnames #",")))))

(schema/defn create-subject-alt-names-ext :- Extension
  "Given a hostname and a comma-separated list of DNS (and possibly IP) alt names,
   create a Subject Alternative Names extension. If there are no alt names
   provided then defaults will be used."
  [host-name :- schema/Str
   alt-names :- schema/Str]
  (let [split-alt-names   (split-hostnames alt-names)
        default-alt-names ["puppet"]
        alt-names-list (reduce (fn [acc alt-name]
                                 (if (str/starts-with? alt-name "IP:")
                                   (update acc :ip conj (str/replace alt-name #"^IP:" ""))
                                   (update acc :dns-name conj (str/replace alt-name #"^DNS:" ""))))
                               {:ip [] :dns-name []} split-alt-names)]
    (if (and (empty? (get alt-names-list :dns-name)) (empty? (get alt-names-list :ip)))
      (utils/subject-alt-names {:dns-name (conj default-alt-names host-name)} false)
      (utils/subject-alt-names (update alt-names-list :dns-name conj host-name) false))))


(def pattern-match-dot #"\.")
(def pattern-starts-with-alphanumeric-or-underscore #"^[\p{Alnum}_].*")
(def pattern-matches-alphanumeric-with-symbols-string #"^[\p{Alnum}\-_]*[\p{Alnum}_]$")

(schema/defn validate-subject!
  "Validate the CSR or certificate's subject name.  The subject name must:
    * match the hostname specified in the HTTP request (the `subject` parameter)
    * not contain any non-printable characters or slashes
    * not contain any capital letters
    * not contain the wildcard character (*)"
  [hostname :- schema/Str
   subject :- schema/Str]
  (log/debug (i18n/trs "Checking \"{0}\" for validity" subject))

  (when-not (= hostname subject)
    ;; see https://github.com/puppetlabs/clj-i18n/blob/main/README.md#single-quotes-in-messages for reasoning with double quote
    (log/info (i18n/tru "Rejecting subject \"{0}\" because it doesn''t match hostname \"{1}\"" subject hostname))
    (sling/throw+
      {:kind :hostname-mismatch
       :msg  (i18n/tru "Instance name \"{0}\" does not match requested key \"{1}\"" subject hostname)}))

  (when (contains-uppercase? hostname)
    (log/info (i18n/tru "Rejecting subject \"{0}\" because all characters must be lowercase" subject))
    (sling/throw+
      {:kind :invalid-subject-name
       :msg  (i18n/tru "Certificate names must be lower case.")}))

  (when (.contains subject "*")
    (sling/throw+
      {:kind :invalid-subject-name
       :msg  (i18n/tru "Subject contains a wildcard, which is not allowed: {0}" subject)}))

  (when (str/ends-with? subject "-")
    (log/info (i18n/tru "Rejecting subject \"{0}\" as it ends with an invalid character" subject))
    (sling/throw+
     {:kind :invalid-subject-name
      :msg  (i18n/tru "Subject hostname format is invalid")}))

  (let [segments (str/split subject pattern-match-dot)]
    (when-not (re-matches pattern-starts-with-alphanumeric-or-underscore (first segments))
      (log/info (i18n/tru "Rejecting subject \"{0}\" as it starts with an invalid character" subject))
      (sling/throw+
        {:kind :invalid-subject-name
         :msg  (i18n/tru "Subject hostname format is invalid")}))

    (when-not (every? #(re-matches pattern-matches-alphanumeric-with-symbols-string %) segments)
      (log/info (i18n/tru "Rejecting subject \"{0}\" because it contains invalid characters" subject))
      (sling/throw+
        {:kind :invalid-subject-name
         :msg  (i18n/tru "Subject hostname format is invalid")}))))

(schema/defn allowed-extension?
  "A predicate that answers if an extension is allowed or not.
  This logic is copied out of the ruby CA."
  [extension :- Extension]
  (let [oid (:oid extension)]
    (or
      (= subject-alt-names-oid oid)
      (and (utils/subtree-of? ppAuthCertExt oid) (not (= cli-auth-oid oid)))
      (utils/subtree-of? ppRegCertExt oid)
      (utils/subtree-of? ppPrivCertExt oid))))

(schema/defn validate-extensions!
  "Throws an error if the extensions list contains any invalid extensions,
  according to `allowed-extension?`"
  [extensions :- (schema/pred utils/extension-list?)]
  (let [bad-extensions (remove allowed-extension? extensions)]
    (when-not (empty? bad-extensions)
      (let [bad-extension-oids (map :oid bad-extensions)]
        (sling/throw+ {:kind :disallowed-extension
                       :msg (i18n/tru "Found extensions that are not permitted: {0}"
                                 (str/join ", " bad-extension-oids))})))))

(schema/defn validate-subject-alt-names!
  "Validate that the provided Subject Alternative Names extension is valid for
  a cert signed by this CA. This entails:
    * Only DNS and IP alternative names are allowed, no other types
    * Each DNS name does not contain a wildcard character (*)"
  [{value :value} :- Extension]
  (let [name-types (keys value)
        dns-names (:dns-name value)]
    (when-not (every? #{:dns-name :ip} name-types)
      (sling/throw+
        {:kind :invalid-alt-name
         :msg (i18n/tru "Only DNS and IP names are allowed in the Subject Alternative Names extension")}))
    (doseq [dns-name dns-names]
      (when (.contains dns-name "*")
        (sling/throw+
          {:kind :invalid-alt-name
           :msg (i18n/tru "Cert subjectAltName contains a wildcard, which is not allowed: {0}"
                 dns-name)})))))

(schema/defn create-csr-attrs-exts :- (schema/maybe (schema/pred utils/extension-list?))
  "Parse the CSR attributes yaml file at the given path and create a list of
  certificate extensions from the `extensions_requests` section."
  [csr-attributes-file :- schema/Str]
  (if (fs/file? csr-attributes-file)
    (let [csr-attrs (common/parse-yaml (slurp csr-attributes-file))
          ext-req (:extension_requests csr-attrs)]
      (map (fn [[oid value]]
             {:oid (or (get puppet-short-names oid)
                       (name oid))
              :critical false
              :value (str value)})
           ext-req))
    (log/debug (i18n/trs "No CSR Attributes configuration file found at {0}, CSR Attributes will not be loaded"
                csr-attributes-file))))

(schema/defn create-master-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the master certificate."
  [master-certname :- schema/Str
   master-public-key :- (schema/pred utils/public-key?)
   ca-cert :- Certificate
   {:keys [dns-alt-names csr-attributes]} :- MasterSettings]
  (let [alt-names-ext (create-subject-alt-names-ext master-certname dns-alt-names)
        csr-attr-exts (create-csr-attrs-exts csr-attributes)
        base-ext-list [(utils/netscape-comment
                         netscape-comment-value)
                       (utils/authority-key-identifier-options ca-cert)
                       (utils/basic-constraints-for-non-ca true)
                       (utils/ext-key-usages
                         [ssl-server-cert ssl-client-cert] true)
                       (utils/key-usage
                         #{:key-encipherment
                           :digital-signature} true)
                       (utils/subject-key-identifier
                         master-public-key false)
                       {:oid cli-auth-oid
                        :critical false
                        :value "true"}]]
    (validate-subject-alt-names! alt-names-ext)
    (when csr-attr-exts
      (validate-extensions! csr-attr-exts))
    (remove nil? (concat base-ext-list [alt-names-ext] csr-attr-exts))))

(schema/defn generate-master-ssl-keys!* :- (schema/pred utils/public-key?)
  "Generate and store ssl public and private keys for the master to disk.
  Returns the public key."
  [{:keys [hostprivkey hostpubkey keylength]} :- MasterSettings]
  (log/debug (i18n/trs "Generating public and private keys for master cert"))
  (let [keypair (utils/generate-key-pair keylength)
        public-key (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)]
    (write-public-key public-key hostpubkey)
    (write-private-key private-key hostprivkey)
    public-key))

(schema/defn generate-master-ssl-keys! :- (schema/pred utils/public-key?)
  "Generate and store ssl public and private keys for the master to disk.  If
  the files are both already present, new ones will not be generated to replace
  them.  Returns the public key."
  [{:keys [hostprivkey hostpubkey] :as settings} :- MasterSettings]
  (cond
    (and (fs/exists? hostprivkey) (fs/exists? hostpubkey))
    (do
      (log/debug (i18n/trs "Using keys found on disk to generate master cert"))
      (utils/pem->public-key hostpubkey))

    (and (not (fs/exists? hostprivkey)) (not (fs/exists? hostpubkey)))
    (generate-master-ssl-keys!* settings)

    (fs/exists? hostpubkey)
    (throw
     (IllegalStateException.
      ^String (i18n/trs "Found master public key ''{0}'' but master private key ''{1}'' is missing"
                hostpubkey
                hostprivkey)))

    :else
    (throw
     (IllegalStateException.
      ^String (i18n/trs "Found master private key ''{0}'' but master public key ''{1}'' is missing"
                hostprivkey
                hostpubkey)))))

(schema/defn generate-master-ssl-files!
  "Given master configuration settings, certname, and CA settings,
   generate and write to disk all of the necessary SSL files for
   the master. Any existing files will be replaced."
  [settings :- MasterSettings
   certname :- schema/Str
   ca-settings :- CaSettings]
  (log/debug (format "%s\n%s"
                     (i18n/trs "Initializing SSL for the Master; settings:")
                     (ks/pprint-to-string settings)))
  (create-parent-directories! (vals (settings->ssldir-paths settings)))
  (ks-file/set-perms (:privatekeydir settings) private-key-dir-perms)
  (-> settings :certdir fs/file ks/mkdirs!)
  (-> settings :requestdir fs/file ks/mkdirs!)
  (let [ca-cert        (utils/pem->ca-cert (:cacert ca-settings) (:cakey ca-settings))
        ca-private-key (utils/pem->private-key (:cakey ca-settings))
        next-serial    (next-serial-number! ca-settings)
        public-key     (generate-master-ssl-keys! settings)
        extensions     (create-master-extensions certname
                                                 public-key
                                                 ca-cert
                                                 settings)
        x500-name      (utils/cn certname)
        validity       (cert-validity-dates (:ca-ttl ca-settings))
        hostcert       (utils/sign-certificate (utils/get-subject-from-x509-certificate
                                                ca-cert)
                                               ca-private-key
                                               next-serial
                                               (:not-before validity)
                                               (:not-after validity)
                                               x500-name
                                               public-key
                                               extensions)]
    (write-cert-to-inventory! hostcert ca-settings)
    (write-cert hostcert (:hostcert settings))
    (write-cert hostcert (path-to-cert (:signeddir ca-settings) certname))))

(schema/defn ^:always-validate initialize-master-ssl!
  "Given configuration settings, certname, and CA settings, ensure all
   necessary SSL files exist on disk by regenerating all of them if any
   are found to be missing."
  [{:keys [hostprivkey hostcert] :as settings} :- MasterSettings
   certname :- schema/Str
   ca-settings :- CaSettings]
  (cond
    (and (fs/exists? hostcert) (fs/exists? hostprivkey))
    (log/info (i18n/trs "Master already initialized for SSL"))

    (fs/exists? hostcert)
    (throw
     (IllegalStateException.
      ^String (i18n/trs "Found master cert ''{0}'' but master private key ''{1}'' is missing"
                hostcert
                hostprivkey)))

    :else
    (generate-master-ssl-files! settings certname ca-settings)))

(schema/defn ^:always-validate retrieve-ca-cert!
  "Ensure a local copy of the CA cert is available on disk.  cacert is the base
  CA cert file to copy from and localcacert is where the CA cert file should be
  copied to."
  [cacert :- schema/Str
   localcacert :- schema/Str]
  (if (and (fs/exists? cacert) (not (fs/exists? localcacert)))
    (do
      (ks/mkdirs! (fs/parent localcacert))
      (fs/copy cacert localcacert))
    (when-not (fs/exists? localcacert)
      (throw (IllegalStateException.
               ^String (i18n/trs ":localcacert ({0}) could not be found and no file at :cacert ({1}) to copy it from"
                         localcacert cacert))))))

(schema/defn ^:always-validate retrieve-ca-crl!
  "Ensure a local copy of the CA CRL, if one exists, is available on disk.
  cacrl is the base CRL file to copy from and localcacrl is where the CRL file
  should be copied to."
  [cacrl :- schema/Str
   localcacrl :- schema/Str]
  (when (not= cacrl localcacrl)
    (let [max-attempts 25]
      (when (fs/exists? cacrl)
        (ks/mkdirs! (fs/parent localcacrl))
        ;; Make up to 'max-attempts' tries to copy the cacrl file to the
        ;; localcacrl file. The content of the cacrl file is read into memory
        ;; and parsed for valid CRL pem content during each attempt. The content
        ;; in memory is written to the localcacrl file only if it is valid. This
        ;; validation is done to protect against a partially written (which could
        ;; happen if an asynchronous revocation is in progress) or currupt cacrl
        ;; file being copied.
        (loop [attempts-left max-attempts]
           (let [cacrl-as-string (slurp cacrl)]
             (when-let [write-exception (write-local-cacrl!
                                          localcacrl
                                          cacrl-as-string)]
               (if (zero? attempts-left)
                 (log/error (format "%s\n%s\n%s"
                                    (i18n/trs
                                      "Unable to synchronize crl file {0} to {1}: {2}"
                                      cacrl localcacrl (.getMessage write-exception))
                                    (i18n/trs
                                      "Recent changes to the CRL may not have taken effect.")
                                    (i18n/trs
                                      "To load the updated CRL, reload or restart the Puppet Server service.")))
                 (do
                   (Thread/sleep 100)
                   (recur (dec attempts-left)))))))))))

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
    (do (log/error (i18n/trs "Invalid pattern ''{0}'' found in {1}" line whitelist))
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
  :- shell-utils/ExecutionResult
  "Execute the autosign script and return a map containing the standard-out,
   standard-err, and exit code. The subject will be passed in as input, and
   the CSR stream will be provided on standard-in. The ruby-load-path will be
   prepended to the RUBYLIB found in the environment, and is intended to make
   the Puppet and Facter Ruby libraries available to the autosign script.
   All output (stdout & stderr) will be logged at the debug level. Warnings are
   issued for nonzero exit code or if stderr is generated by the autosign
   script."
  [executable :- schema/Str
   subject :- schema/Str
   csr-stream :- InputStream
   ruby-load-path :- [schema/Str]
   gem-path :- schema/Str]
  (log/debug (i18n/trs "Executing ''{0} {1}''" executable subject))
  (let [env     (into {} (System/getenv))
        rubylib (->> (if-let [lib (get env "RUBYLIB")]
                       (cons lib ruby-load-path)
                       ruby-load-path)
                     (map ks/absolute-path)
                     (str/join (System/getProperty "path.separator")))
        gempath (if-let [gems (get env "GEM_PATH")]
                  (str gems (System/getProperty "path.separator") gem-path)
                  gem-path)
        results (shell-utils/execute-command
                 executable
                 {:args [subject]
                  :in csr-stream
                  :env (merge env {"RUBYLIB" rubylib "GEM_PATH" gempath})})]
    (log/debug (i18n/trs "Autosign command ''{0} {1}'' exit status: {2}"
                executable subject (:exit-code results)))
    (log/debug (i18n/trs "Autosign command ''{0} {1}'' output on stdout: {2}"
                executable subject (:stdout results)))
    (when-not (empty? (:stderr results))
      (log/warn (i18n/trs "Autosign command ''{0} {1}'' generated output to stderr: {2}"
                 executable subject (:stderr results))))
    (when-not (zero? (:exit-code results))
      (log/warn (i18n/trs "Autosign command ''{0}'' rejected certificate ''{1}'' because the exit code was {2}, not zero"
                 executable subject (:exit-code results))))
    results))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  config->ca-settings :- CaSettings
  "Given the configuration map from the Puppet Server config
   service return a map with of all the CA settings."
  [{:keys [puppetserver jruby-puppet certificate-authority authorization]}]
  (let [merged (-> (select-keys puppetserver (keys CaSettings))
                   (merge (select-keys certificate-authority (keys CaSettings)))
                   (initialize-ca-config))]
    (assoc merged :ruby-load-path (:ruby-load-path jruby-puppet)
           :allow-auto-renewal (:allow-auto-renewal merged)
           :auto-renewal-cert-ttl (duration-str->sec (:auto-renewal-cert-ttl merged))
           :ca-ttl (get-ca-ttl puppetserver certificate-authority)
           :allow-header-cert-info (get authorization :allow-header-cert-info false)
           :gem-path (str/join (System/getProperty "path.separator")
                               (:gem-path jruby-puppet))
           :access-control (select-keys certificate-authority
                                        [:certificate-status])
           :serial-lock (new ReentrantReadWriteLock)
           :crl-lock (new ReentrantReadWriteLock)
           :inventory-lock (new ReentrantLock))))

(schema/defn ^:always-validate
  config->master-settings :- MasterSettings
  "Given the configuration map from the Puppet Server config
  service return a map with of all the master settings."
  [{:keys [puppetserver]}]
  (select-keys puppetserver (keys MasterSettings)))

(schema/defn ^:always-validate get-certificate-path :- (schema/maybe schema/Str)
  "Given a subject name and paths to the CA certificate and path
  to the certificate directory return the path to the subject's
  certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject :- schema/Str
   cacert :- schema/Str
   signeddir :- schema/Str]
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert signeddir subject))]
    (when (fs/exists? cert-path)
      cert-path)))

(schema/defn ^:always-validate
  get-certificate :- (schema/maybe schema/Str)
  "Given a subject name and path to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject :- schema/Str
   cacert :- schema/Str
   signeddir :- schema/Str]
  (when-let [cert-path (get-certificate-path subject cacert signeddir)]
    (slurp cert-path)))

(schema/defn ^:always-validate
  get-certificate-request :- (schema/maybe schema/Str)
  "Given a subject name, return their certificate request as a string, or nil if
  not found.  Looks for certificate requests in `csrdir`."
  [subject :- schema/Str
   csrdir :- schema/Str]
  (let [cert-request-path (path-to-cert-request csrdir subject)]
    (when (fs/exists? cert-request-path)
      (slurp cert-request-path))))

(schema/defn ^:always-validate
  autosign-csr? :- schema/Bool
  "Return true if the CSR should be automatically signed given
  Puppet's autosign setting, and false otherwise."
  ([autosign :- AutoSignInput
    subject :- schema/Str
    csr-stream :- InputStream]
   (autosign-csr? autosign subject csr-stream [] ""))
  ([autosign :- AutoSignInput
    subject :- schema/Str
    csr-stream :- InputStream
    ruby-load-path :- [schema/Str]
    gem-path :- schema/Str]
   (if (ks/boolean? autosign)
     autosign
     (if (fs/exists? autosign)
       (if (fs/executable? autosign)
         (let [command-result (execute-autosign-command! autosign subject csr-stream ruby-load-path gem-path)
               succeed? (zero? (:exit-code command-result))]
           (when-not succeed?
             (log/debug (i18n/trs "Autosign executable failed. Result: {0} " (pr-str command-result))))
           succeed?)
         (whitelist-matches? autosign subject))
       false))))

(schema/defn create-agent-extensions :- (schema/pred utils/extension-list?)
  "Given a certificate signing request, generate a list of extensions that
  should be signed onto the certificate. This includes a base set of standard
  extensions in addition to any valid extensions found on the signing request."
  [csr :- CertificateRequest
   cacert :- Certificate]
  (let [subj-pub-key (utils/get-public-key csr)
        csr-ext-list (utils/get-extensions csr)
        base-ext-list [(utils/netscape-comment
                         netscape-comment-value)
                       (utils/authority-key-identifier-options
                         cacert)
                       (utils/basic-constraints-for-non-ca true)
                       (utils/ext-key-usages
                         [ssl-server-cert ssl-client-cert] true)
                       (utils/key-usage
                         #{:key-encipherment
                           :digital-signature} true)
                       (utils/subject-key-identifier
                        subj-pub-key false)]
        subject (get-csr-subject csr)
        combined-list (vec (concat base-ext-list csr-ext-list))]
    (ensure-ext-list-has-cn-san subject combined-list)))

(defn report-cert-event
  "Log message and report to the activity service if available about cert activties, ie signing and revoking."
  [report-activity message subject certnames ip-address activity-type]
  (let [commit {:service {:id "puppet-ca"}
                :subject {:id subject
                          :name subject
                          :type "users"}
                :objects (mapv (fn [cert] {:type "node" :id cert :name cert}) certnames)
                :events [{:type (str activity-type "-certificate")
                          :what "node"
                          :description (str "certificate_successfully_" activity-type)
                          :message message}]
                :ip_address ip-address}]
    (log/info message)
    (report-activity {:commit commit})))

(defn generate-cert-message-from-request
  "Extract params from request and create successful cert signing message.
  Returns message, subject, certname and ip address"
  [request subjects activity-type]
  (let [auth-name (get-in request [:authorization :name])
        rbac-user (get-in request [:rbac-subject :login])
        ip-address (:remote-addr request)
        signee (first (remove clojure.string/blank? [rbac-user auth-name "CA"]))]

    [(i18n/trsn "Entity {1} {2} 1 certificate: {3}."
                "Entity {1} {2} {0} certificates: {3}."
                (count subjects) signee activity-type (str/join ", " subjects))
     signee
     subjects
     ip-address]))

(defn create-report-activity-fn
  [report-activity request]
  (fn [subjects activity-type]
    (let [[msg signee certnames ip] (generate-cert-message-from-request request subjects activity-type)]
      (report-cert-event report-activity msg signee certnames ip activity-type))))

(schema/defn supports-auto-renewal? :- schema/Bool
  "Given a csr, determine if the requester is capable of supporting auto-renewal by looking for a specific attribute"
  [csr]
  (if-let [auto-renew-attribute (first (filter #(= pp_auth_auto_renew-attribute (:oid %)) (get-csr-attributes csr)))]
    (do
      (log/debug (i18n/trs "Found auto-renew-attribute {0}" (first (:values auto-renew-attribute))))
      ;; the values is a sequence of results, assume the first one is correct.
      (= "true" (first (:values auto-renew-attribute))))
    false))

(schema/defn ^:always-validate delete-certificate-request! :- OutcomeInfo
  "Delete pending certificate requests for subject"
  [{:keys [csrdir]} :- CaSettings
   subject :- schema/Str]
  (let [csr-path (path-to-cert-request csrdir subject)]

    (if (fs/exists? csr-path)
      (if (fs/delete csr-path)
        (let [msg (i18n/trs "Deleted certificate request for {0} at {1}" subject csr-path)]
          (log/debug msg)
          {:outcome :success
           :message msg})
        (let [msg (i18n/trs "Path {0} exists but could not be deleted" csr-path)]
          (log/error msg)
          {:outcome :error
           :message msg}))
      (let [msg (i18n/trs "No certificate request for {0} at expected path {1}"
                          subject csr-path)]
        (log/warn msg)
        {:outcome :not-found
         :message msg}))))

(schema/defn ^:always-validate
  autosign-certificate-request!
  "Given a subject name, their certificate request, and the CA settings
  from Puppet, auto-sign the request and write the certificate to disk."
  [subject :- schema/Str
   csr :- CertificateRequest
   {:keys [cacert cakey signeddir ca-ttl allow-auto-renewal auto-renewal-cert-ttl] :as ca-settings} :- CaSettings
   report-activity]
  (let [renewal-ttl (if (and allow-auto-renewal (supports-auto-renewal? csr))
                      auto-renewal-cert-ttl
                      ca-ttl)
        _           (log/debug (i18n/trs "Calculating validity dates for {0} from ttl of {1} " subject renewal-ttl))
        validity    (cert-validity-dates renewal-ttl)
        ;; if part of a CA bundle, the intermediate CA will be first in the chain
        cacert      (utils/pem->ca-cert cacert cakey)
        signed-cert (utils/sign-certificate (utils/get-subject-from-x509-certificate
                                             cacert)
                                            (utils/pem->private-key cakey)
                                            (next-serial-number! ca-settings)
                                            (:not-before validity)
                                            (:not-after validity)
                                            (utils/cn subject)
                                            (utils/get-public-key csr)
                                            (create-agent-extensions
                                             csr
                                             cacert))]
    (write-cert-to-inventory! signed-cert ca-settings)
    (write-cert signed-cert (path-to-cert signeddir subject))
    (delete-certificate-request! ca-settings subject)
    (report-activity [subject] "signed")))

(schema/defn ^:always-validate
  save-certificate-request!
  "Write the subject's certificate request to disk under the CSR directory."
  [subject :- schema/Str
   csr :- CertificateRequest
   csrdir :- schema/Str]
  (let [csr-path (path-to-cert-request csrdir subject)]
    (log/debug (i18n/trs "Saving CSR to ''{0}''" csr-path))
    (write-csr csr csr-path)))

(schema/defn validate-duplicate-cert-policy!
  "Throw a slingshot exception if allow-duplicate-certs is false,
   and we already have a certificate or CSR for the subject.
   The exception map will look like:
   {:kind :duplicate-cert
    :msg  <specific error message>}"
  [csr :- CertificateRequest
   {:keys [allow-duplicate-certs cacert cacrl crl-lock crl-lock-timeout-seconds cakey csrdir signeddir]} :- CaSettings]
  (let [subject (get-csr-subject csr)
        cert (path-to-cert signeddir subject)
        existing-cert? (fs/exists? cert)
        existing-csr? (fs/exists? (path-to-cert-request csrdir subject))]
    (when (or existing-cert? existing-csr?)
      (let [status (if existing-cert?
                     (if (utils/revoked?
                           (common/with-safe-read-lock crl-lock crl-lock-descriptor crl-lock-timeout-seconds
                             (utils/pem->ca-crl cacrl (utils/pem->ca-cert cacert cakey)))
                          (utils/pem->cert cert))
                       "revoked"
                       "signed")
                     "requested")]
        (if allow-duplicate-certs
          (log/info (i18n/trs "{0} already has a {1} certificate; new certificate will overwrite it" subject status))
          (sling/throw+
            {:kind :duplicate-cert
             :msg (i18n/tru "{0} already has a {1} certificate; ignoring certificate request" subject status)}))))))

(schema/defn validate-csr-signature!
  "Throws an exception when the CSR's signature is invalid.
  See `signature-valid?` for more detail."
  [certificate-request :- CertificateRequest]
  (when-not (utils/signature-valid? certificate-request)
    (sling/throw+
      {:kind :invalid-signature
       :msg (i18n/tru "CSR contains a public key that does not correspond to the signing key")})))

(schema/defn ensure-no-authorization-extensions!
  "Throws an exception if the CSR contains authorization exceptions AND the user
   has chosen to disallow authorization-extensions.  This ensures that
   certificates with authentication extensions can only be signed intentionally."
  [csr :- CertificateRequest
   allow-authorization-extensions :- schema/Bool]
  (let [extensions (utils/get-extensions csr)]
    (doseq [extension extensions]
      (when (utils/subtree-of? ppAuthCertExt (:oid extension))
        (when (false? allow-authorization-extensions)
          (sling/throw+
            {:kind :disallowed-extension
             :msg (format "%s %s %s"
                          (i18n/trs "CSR ''{0}'' contains an authorization extension, which is disallowed." (get-csr-subject csr))
                          (i18n/trs "To allow authorization extensions, set allow-authorization-extensions to true in your ca.conf file.")
                          (i18n/trs "Then restart the puppetserver and try signing this certificate again."))}))))))

(schema/defn ensure-subject-alt-names-allowed!
  "Throws an exception if the CSR contains subject-alt-names AND the user has
   chosen to disallow subject-alt-names. Subject alt names can be allowed by
   setting allow-subject-alt-names to true in the ca.conf file. Always allows
   a single subject alt name that matches the CSR subject, which may be
   present to comply with RFC 2818 (see SERVER-2338)."
  [csr :- CertificateRequest
   allow-subject-alt-names :- schema/Bool]
  (when-let [subject-alt-names (not-empty (subject-alt-names csr))]
    (when (false? allow-subject-alt-names)
      (let [subject (get-csr-subject csr)
            cn-alt-name (str "DNS:" subject)]
        (if (and (= 1 (count subject-alt-names))
                 (= (first subject-alt-names) cn-alt-name))
          (log/debug "Allowing subject alt name that matches CSR subject.")
          (let [disallowed-alt-names (filter #(not (= cn-alt-name %))
                                             subject-alt-names)]
            (sling/throw+
             {:kind :disallowed-extension
              :msg (format "%s %s %s"
                           (i18n/tru "CSR ''{0}'' contains extra subject alternative names ({1}), which are disallowed."
                                     subject (str/join ", " disallowed-alt-names))
                           (i18n/tru "To allow subject alternative names, set allow-subject-alt-names to true in your ca.conf file.")
                           (i18n/tru "Then restart the puppetserver and try signing this certificate again."))})))))))


(schema/defn ^:always-validate process-csr-submission!
  "Given a CSR for a subject (typically from the HTTP endpoint),
   perform policy checks and sign or save the CSR (based on autosign).
   Throws a slingshot exception if the CSR is invalid."
  [subject :- schema/Str
   certificate-request :- InputStream
   {:keys [autosign csrdir ruby-load-path gem-path allow-subject-alt-names allow-authorization-extensions] :as settings} :- CaSettings
   report-activity]
  (with-open [byte-stream (-> certificate-request
                              input-stream->byte-array
                              ByteArrayInputStream.)]
    (let [csr (utils/pem->csr byte-stream)
          csr-stream (doto byte-stream .reset)]
      (validate-duplicate-cert-policy! csr settings)
      (validate-subject! subject (get-csr-subject csr))
      (save-certificate-request! subject csr csrdir)
      (when (autosign-csr? autosign subject csr-stream ruby-load-path gem-path)
        (ensure-subject-alt-names-allowed! csr allow-subject-alt-names)
        (ensure-no-authorization-extensions! csr allow-authorization-extensions)
        (validate-extensions! (utils/get-extensions csr))
        (validate-csr-signature! csr)
        (autosign-certificate-request! subject csr settings report-activity)))))


(schema/defn ^:always-validate
  get-certificate-revocation-list :- schema/Str
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl :- schema/Str
   lock :- ReentrantReadWriteLock
   lock-descriptor :- schema/Str
   lock-timeout :- PosInt]
  (common/with-safe-read-lock lock lock-descriptor lock-timeout
    (slurp cacrl)))

(schema/defn ^:always-validate
  get-file-last-modified :- DateTime
  "Given a path to a file, return a Joda DateTime instance of when the file was last modified.
   an optional lock, description, and timeout may be passed to serialize access to files."
  ([path :- schema/Str]
   (let [last-modified-milliseconds (.lastModified (io/file path))]
        (time-coerce/from-long last-modified-milliseconds)))
  ([path :- schema/Str
    lock :- ReentrantReadWriteLock
    lock-descriptor :- schema/Str
    lock-timeout :- PosInt]
   (common/with-safe-read-lock lock lock-descriptor lock-timeout
     (let [last-modified-milliseconds (.lastModified (io/file path))]
          (time-coerce/from-long last-modified-milliseconds)))))

(schema/defn ^:always-validate reject-delta-crl
  [crl :- CertificateRevocationList]
  (when (utils/delta-crl? crl)
    (throw (IllegalArgumentException.
            ^String (i18n/trs "Cannot support delta CRL.")))))

(schema/defn ^:always-validate validate-certs-and-crls
  "Given a list of certificates and a list of CRLs, validate the certificate
   chain, i.e. ensure that none of the certs have been revoked by checking the
   appropriate CRL, which must be present and currently valid. Delta CRLs are
   not supported. Returns nil if successful."
  [cert-chain :- [Certificate]
   crl-chain :- [CertificateRevocationList]]
  (doseq [crl crl-chain]
    (reject-delta-crl crl))
  (try
    (utils/validate-cert-chain cert-chain crl-chain)
    ;; currently not distinguishing between invalid CRLs
    ;; and a revoked cert in the CA chain
    (catch CertPathValidatorException e
      (throw (IllegalArgumentException.
              ^String (i18n/trs "Invalid certs and/or CRLs: {0}" (.getMessage e)))))))

(schema/defn ^:always-validate get-newest-crl :- CertificateRevocationList
  "Determine the newest CRL by looking for the highest CRL Number. This assumes
  all given CRLs have the same issuer. Fails if more than one CRL has the
  highest CRL Number."
  [crls :- [CertificateRevocationList]]
  (let [newest-crl (->> crls
                        (group-by utils/get-crl-number)
                        (into (sorted-map))
                        last
                        last)]
    (if (= 1 (count newest-crl))
      (first newest-crl)
      (throw (IllegalArgumentException.
              ^String (i18n/trs "Could not determine newest CRL."))))))

(schema/defn ^:always-validate maybe-replace-crl :- CertificateRevocationList
  "Given a CRL and a map of key identifiers to CRLs, determine the
  newest CRL with the key-id of the given CRL. Warn if the newest CRL
  is the given CRL. Never replaces the CRL corresponding to the Puppet
  CA signing cert."
  [crl :- CertificateRevocationList
   key-crl-map :- {KeyIdExtension [CertificateRevocationList]}]
  (let [key-id-ext (utils/get-extension-value crl utils/authority-key-identifier-oid)
        maybe-new-crls (get key-crl-map key-id-ext)]
    (if maybe-new-crls
      (let [new-crl (get-newest-crl (conj maybe-new-crls crl))
            issuer (.getIssuerX500Principal crl)]
        (if (.equals crl new-crl)
          (log/warn (i18n/trs
                     "Received CRLs for issuer {0} but none were newer than the existing CRL; keeping the existing CRL."
                     issuer))
          (log/info (i18n/trs
                     "Updating CRL for issuer {0}." issuer)))
        new-crl)
      ;; no new CRLs found for this issuer, keep it
      crl)))

(schema/defn get-auth-key-id
  [crl :- X509CRL]
  (if-let [key-id (utils/get-extension-value crl utils/authority-key-identifier-oid)]
    key-id
    (throw (IllegalArgumentException. ^String (i18n/trs "One or more submitted CRLs do not have an authority key identifier.")))))

(schema/defn ^:always-validate update-crls
  "Given a collection of CRLs, update the CRL chain and confirm that
  all CRLs are currently valid.
  NOTE: assumes appropriate locking is in place"
  [incoming-crls :- [X509CRL]
   crl-path :- schema/Str
   cert-chain-path :- schema/Str]
  (log/info (i18n/trs "Processing update to CRL at {0}" crl-path))
  (let [current-crls (utils/pem->crls crl-path)
        cert-chain (utils/pem->certs cert-chain-path)
        ca-cert-key (utils/get-extension-value (first cert-chain)
                                               utils/subject-key-identifier-oid)
        external-crl-chain (remove #(= ca-cert-key
                                       (:key-identifier (utils/get-extension-value % utils/authority-key-identifier-oid)))
                                   current-crls)
        ca-crl (first (filter
                       #(= ca-cert-key
                           (:key-identifier (utils/get-extension-value % utils/authority-key-identifier-oid)))
                       current-crls))
        incoming-crls-by-key-id (->> incoming-crls
                                     ;; just in case we're given multiple copies
                                     ;; of the same CRL, deduplicate so we can
                                     ;; identify the newest CRL
                                     set
                                     (group-by get-auth-key-id))
        new-ext-crl-chain (cons ca-crl (map #(maybe-replace-crl % incoming-crls-by-key-id)
                                            external-crl-chain))]
    (validate-certs-and-crls cert-chain new-ext-crl-chain)
    (write-crls new-ext-crl-chain crl-path)
    (log/info (i18n/trs "Successfully updated CRL at {0}" crl-path))))

(schema/defn update-crls!
  "Apply write locking to the crls, and update the crls as appropriate."
  [incoming-crls :- [X509CRL]
   crl-path :- schema/Str
   cacert :- schema/Str
   {:keys [crl-lock crl-lock-timeout-seconds enable-infra-crl infra-crl-path]}  :- CaSettings]
  (common/with-safe-write-lock crl-lock crl-lock-descriptor crl-lock-timeout-seconds
    (update-crls incoming-crls crl-path cacert)
    (when enable-infra-crl
      (update-crls incoming-crls infra-crl-path cacert))))

(schema/defn ensure-directories-exist!
  "Create any directories used by the CA if they don't already exist."
  [settings :- CaSettings]
  (doseq [dir [:csrdir :signeddir]]
    (let [path (get settings dir)]
      (when-not (fs/exists? path)
        (ks/mkdirs! path)))))

(schema/defn ensure-ca-file-perms!
  "Ensure that the CA's private key file has the correct permissions set. If it
  does not, then correct them."
  [settings :- CaSettings]
  (let [ca-p-key (:cakey settings)
        cur-perms (ks-file/get-perms ca-p-key)]
    (when-not (= private-key-perms cur-perms)
      (ks-file/set-perms ca-p-key private-key-perms)
      (log/warn (format "%s %s"
                        (i18n/trs "The private CA key at ''{0}'' was found to have the wrong permissions set as ''{1}''."
                             ca-p-key cur-perms)
                        (i18n/trs "This has been corrected to ''{0}''."
                             private-key-perms))))))

(schema/defn ^:always-validate
  initialize!
  "Given the CA configuration settings, ensure that all
   required SSL files exist. If all files exist,
   new ones will not be generated. If only some are found
   (but others are missing), an exception is thrown."
  [settings :- CaSettings]
  (ensure-directories-exist! settings)
  (let [required-files (-> (settings->cadir-paths settings)
                           (select-keys (required-ca-files (:enable-infra-crl settings)))
                           (vals))]
    (if (every? fs/exists? required-files)
      (do
        (log/info (i18n/trs "CA already initialized for SSL"))
        (when (:enable-infra-crl settings)
          (generate-infra-serials settings))
        (when (:manage-internal-file-permissions settings)
          (ensure-ca-file-perms! settings)))
      (let [{found true
             missing false} (group-by fs/exists? required-files)]
        (if (= required-files missing)
          (generate-ssl-files! settings)
          (throw (partial-state-error "CA" found missing)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; certificate_status endpoint

(schema/defn certificate-state :- CertificateState
  "Determine the state a certificate is in."
  [cert-or-csr :- CertificateOrCSR
   crl :- CertificateRevocationList]
  (if (utils/certificate-request? cert-or-csr)
    "requested"
    (if (utils/revoked? crl cert-or-csr)
      "revoked"
      "signed")))

(schema/defn fingerprint :- schema/Str
  "Calculate the hash of the certificate or CSR using the given
   algorithm, which must be one of SHA-1, SHA-256, or SHA-512."
  [cert-or-csr :- CertificateOrCSR
   algorithm :- schema/Str]
  (->> (utils/fingerprint cert-or-csr algorithm)
       (partition 2)
       (map (partial apply str))
       (str/join ":")
       (str/upper-case)))

(schema/defn get-certificate-details :- CertificateDetails
  "Return details from a X509 certificate."
  [cert]
  {:not_after     (-> cert
                    (.getNotAfter)
                    (format-date-time))
   :not_before    (-> cert
                    (.getNotBefore)
                    (format-date-time))
   :serial_number (-> cert
                    (.getSerialNumber))})

(schema/defn get-cert-or-csr-status*
  [crl :- CertificateRevocationList
   is-cert? :- schema/Bool
   subject :- schema/Str
   cert-or-csr :- CertificateOrCSR]
  (let [default-fingerprint (fingerprint cert-or-csr "SHA-256")]
    (merge
      {:name              subject
       :state             (certificate-state cert-or-csr crl)
       :dns_alt_names     (dns-alt-names cert-or-csr)
       :subject_alt_names (subject-alt-names cert-or-csr)
       :authorization_extensions (authorization-extensions cert-or-csr)
       :fingerprint       default-fingerprint
       :fingerprints      {:SHA1    (fingerprint cert-or-csr "SHA-1")
                           :SHA256  default-fingerprint
                           :SHA512  (fingerprint cert-or-csr "SHA-512")
                           :default default-fingerprint}}
      ;; Only certificates have expiry dates
      (when is-cert?
        (get-certificate-details cert-or-csr)))))

(schema/defn ^:always-validate get-cert-or-csr-status :- CertificateStatusResult
  "Get the status of the subject's certificate or certificate request.
   The status includes the state of the certificate (signed, revoked, requested),
   DNS alt names, and several different fingerprint hashes of the certificate."
  [{:keys [csrdir signeddir cacert cacrl cakey]} :- CaSettings
   subject :- schema/Str]
  (let [crl (utils/pem->ca-crl cacrl (utils/pem->ca-cert cacert cakey))
        cert-path (path-to-cert signeddir subject)
        is-cert? (fs/exists? cert-path)
        cert-or-csr (if is-cert?
                      (utils/pem->cert cert-path)
                      (utils/pem->csr (path-to-cert-request csrdir subject)))]
    (get-cert-or-csr-status* crl is-cert? subject cert-or-csr)))

(schema/defn ^:always-validate get-cert-or-csr-statuses :- [CertificateStatusResult]
  "Get the statuses of either all the CSR or all the certificate."
  [dir :- schema/Str
   crl :- CertificateRevocationList
   fetch-cert? :- schema/Bool]
  (let [pem-pattern #"^.+\.pem$"
        all-subjects (map #(fs/base-name % ".pem") (fs/find-files dir pem-pattern))
        all-certs-or-csr (if fetch-cert?
                           (map #(utils/pem->cert (path-to-cert dir %)) all-subjects)
                           (map #(utils/pem->csr (path-to-cert-request dir %)) all-subjects))]
    (map (partial get-cert-or-csr-status* crl fetch-cert?) all-subjects all-certs-or-csr)))

(schema/defn ^:always-validate get-cert-and-csr-statuses :- [CertificateStatusResult]
  "Get the status of all certificates and certificate requests."
  [{:keys [csrdir signeddir cacert cacrl cakey]} :- CaSettings]
  (let [crl (utils/pem->ca-crl cacrl (utils/pem->ca-cert cacert cakey))
        all-csr (get-cert-or-csr-statuses csrdir crl false)
        all-certs (get-cert-or-csr-statuses signeddir crl true)]
    (concat all-csr all-certs)))

(schema/defn ^:always-validate filter-by-certificate-state :- [CertificateStatusResult]
  "Get the status of all certificates in the given state."
  [{:keys [csrdir signeddir cacert cacrl cakey]} :- CaSettings
   state :- schema/Str]
  (let [crl (utils/pem->ca-crl cacrl (utils/pem->ca-cert cacert cakey))]
    (if (= "requested" state)
      (get-cert-or-csr-statuses csrdir crl false)
      (->> (get-cert-or-csr-statuses signeddir crl true)
           (filter (fn [cert-status] (= state (:state cert-status))))))))

(schema/defn sign-existing-csr!
  "Sign the subject's certificate request."
  [{:keys [csrdir] :as settings} :- CaSettings
   subject :- schema/Str
   report-activity]
  (let [csr-path (path-to-cert-request csrdir subject)]
    (autosign-certificate-request! subject (utils/pem->csr csr-path) settings report-activity)))

(schema/defn filter-already-revoked-serials :- [schema/Int]
  "Given a list of serials and Puppet's CA CRL, returns vector of serials with
   any already-revoked serials removed."
  [serials :- [schema/Int]
   crl :- X509CRL]
  (let [crl-revoked-list (.getRevokedCertificates crl)
        existed-serials (set (map #(.getSerialNumber %) crl-revoked-list))
        duplicate-serials (set/intersection (set serials) existed-serials)]
    (when (> (count duplicate-serials) 0)
      (doseq [serial duplicate-serials]
        (log/debug (i18n/trs "Certificate with serial {0} is already revoked." serial))))
    (vec (set/difference (set serials) existed-serials))))

(schema/defn revoke-existing-certs!
  "Revoke the subjects' certificates. Note this does not destroy the certificates.
   The certificates will remain in the signed directory despite being revoked."
  [{:keys [signeddir cacert cacrl cakey infra-crl-path
           crl-lock crl-lock-timeout-seconds
           infra-node-serials-path enable-infra-crl]} :- CaSettings
   subjects :- [schema/Str]
   report-activity]
  ;; because we need the crl to be consistent for the serials, maintain a write lock on the crl
  ;; as reentrant read-write locks do not allow upgrading from a read lock to a write lock
  (common/with-safe-write-lock crl-lock crl-lock-descriptor crl-lock-timeout-seconds
    (let [[our-full-crl & rest-of-full-chain] (utils/pem->crls cacrl)
          serials (filter-already-revoked-serials (map #(-> (path-to-cert signeddir %)
                                                            (utils/pem->cert)
                                                            (utils/get-serial))
                                                       subjects)
                                                  our-full-crl)
          serial-count (count serials)]
      (if (= 0 serial-count)
        (log/info (i18n/trs "No revoke action needed. The certs are already in the CRL."))
        (let [new-full-crl (utils/revoke-multiple our-full-crl
                                                  (utils/pem->private-key cakey)
                                                  (.getPublicKey (utils/pem->ca-cert cacert cakey))
                                                  serials)
              new-full-chain (cons new-full-crl (vec rest-of-full-chain))]
          (write-crls new-full-chain cacrl)))

      ;; Publish infra-crl if an infra node is getting revoked.
      (when (and enable-infra-crl (fs/exists? infra-node-serials-path))
        (with-open [infra-nodes-serial-path-reader (io/reader infra-node-serials-path)]
          (let [infra-nodes (set (map biginteger (read-infra-nodes infra-nodes-serial-path-reader)))
                infra-revocations (vec (set/intersection infra-nodes (set serials)))]
            (when (seq infra-revocations)
              (let [[our-infra-crl & rest-of-infra-chain] (utils/pem->crls infra-crl-path)
                    new-infra-revocations (filter-already-revoked-serials infra-revocations our-infra-crl)]
                (if (= 0 new-infra-revocations)
                  (log/info (i18n/trs "No revoke action needed. The infra certs are already in the infra CRL"))
                  (let [new-infra-crl (utils/revoke-multiple our-infra-crl
                                                             (utils/pem->private-key cakey)
                                                             (.getPublicKey (utils/pem->ca-cert cacert cakey))
                                                             new-infra-revocations)
                        full-infra-chain (cons new-infra-crl (vec rest-of-infra-chain))]
                    (write-crls full-infra-chain infra-crl-path)
                    (log/info (i18n/trs "Infra node certificate(s) being revoked; publishing updated infra CRL")))))))))
      (report-activity subjects "revoked"))))

(schema/defn ^:always-validate set-certificate-status!
  "Sign or revoke the certificate for the given subject."
  [settings :- CaSettings
   subject :- schema/Str
   desired-state :- DesiredCertificateState
   report-activity]
  (if (= :signed desired-state)
    (sign-existing-csr! settings subject report-activity)
    (revoke-existing-certs! settings [subject] report-activity)))

(schema/defn ^:always-validate certificate-exists? :- schema/Bool
  "Do we have a certificate for the given subject?"
  [{:keys [signeddir]} :- CaSettings
   subject :- schema/Str]
  (fs/exists? (path-to-cert signeddir subject)))

(schema/defn ^:always-validate csr-exists? :- schema/Bool
  "Do we have a CSR for the given subject?"
  [{:keys [csrdir]} :- CaSettings
   subject :- schema/Str]
  (fs/exists? (path-to-cert-request csrdir subject)))

(defn csr-validation-failure?
  "Does the given object represent a CSR validation failure?
  (thrown from one of the CSR validate-* function, using slingshot)"
  [x]
  (when (map? x)
    (let [kind (:kind x)
          expected-types #{:disallowed-extension
                           :duplicate-cert
                           :hostname-mismatch
                           :invalid-signature
                           :invalid-subject-name}]
      (contains? expected-types kind))))

(schema/defn validate-csr
  "Validates the CSR (on disk) for the specified subject.
   Assumes existence of the CSR on disk; duplicate CSR or
   certificate policy will not be checked.
   If the CSR is invalid, returns a user-facing message.
   Otherwise, returns nil."
  [{:keys [csrdir allow-subject-alt-names allow-authorization-extensions] :as _settings} :- CaSettings
   subject :- schema/Str]
  (let [csr         (utils/pem->csr (path-to-cert-request csrdir subject))
        csr-subject (get-csr-subject csr)
        extensions  (utils/get-extensions csr)]
    (sling/try+
      ;; Matching the order of validations here with
      ;; 'process-csr-submission!' when autosigning
      (validate-subject! subject csr-subject)
      (ensure-subject-alt-names-allowed! csr allow-subject-alt-names)
      (ensure-no-authorization-extensions! csr allow-authorization-extensions)
      (validate-extensions! extensions)
      (validate-csr-signature! csr)

      (catch csr-validation-failure? {:keys [msg]}
        msg))))

(schema/defn ^:always-validate delete-certificate!
  "Delete the certificate for the given subject.
   Note this does not revoke the certificate."
  [{signeddir :signeddir} :- CaSettings
   subject :- schema/Str]
  (let [cert (path-to-cert signeddir subject)]
    (when (fs/exists? cert)
      (fs/delete cert)
      (log/debug (i18n/trs "Deleted certificate for {0}" subject)))))

(schema/defn ^:always-validate delete-certificates!
  "Delete each of the given certificates.
  Note that this does not revoke the certs."
  [ca-settings :- CaSettings
   subjects :- [schema/Str]]
  (doseq [subj subjects]
    (delete-certificate! ca-settings subj)))

(schema/defn ^:always-validate get-custom-oid-mappings :- (schema/maybe OIDMappings)
  "Given a path to a custom OID mappings file, return a map of all oids to
  shortnames"
  [custom-oid-mapping-file :- schema/Str]
  (if (fs/file? custom-oid-mapping-file)
    (let [oid-mappings (:oid_mapping (common/parse-yaml (slurp custom-oid-mapping-file)))]
      (into {} (for [[oid names] oid-mappings] [(name oid) (keyword (:shortname names))])))
    (log/debug (i18n/trs "No custom OID mapping configuration file found at {0}, custom OID mappings will not be loaded"
                custom-oid-mapping-file))))

(schema/defn ^:always-validate get-oid-mappings :- OIDMappings
  [custom-oid-mapping-file :- (schema/maybe schema/Str)]
  (merge (get-custom-oid-mappings custom-oid-mapping-file)
         (clojure.set/map-invert puppet-short-names)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; CA status info

(schema/defn ca-expiration-dates
  "Returns of a map of subject names of certs in the CA bundle
  to their expiration dates."
  [ca-cert-file :- schema/Str]
  (let [ca-cert-bundle (utils/pem->certs ca-cert-file)]
    (reduce (fn [cert-expirations cert]
              (assoc cert-expirations
                (-> cert
                    (.getSubjectDN)
                    (.getName)
                    (utils/x500-name->CN))
                (-> cert
                    (.getNotAfter)
                    (format-date-time))))
            {}
            ca-cert-bundle)))

(schema/defn crl-expiration-dates
  [crl-chain-file :- schema/Str]
  (let [crl-chain (utils/pem->crls crl-chain-file)]
    (reduce (fn [crl-expirations crl]
              (assoc crl-expirations
                (-> crl
                    (.getIssuerDN)
                    (.getName)
                    (utils/x500-name->CN))
                (-> crl
                    (.getNextUpdate)
                    (format-date-time))))
            {}
            crl-chain)))

(schema/defn cert-authority-id-match-ca-subject-id? :- schema/Bool
  "Given a certificate, and the ca-cert, validate that the certificate was signed by the CA provided"
  [incoming-cert :- X509Certificate
   ca-cert :- X509Certificate]
  (let [incoming-key-id (utils/get-extension-value incoming-cert utils/authority-key-identifier-oid)
        ca-key-id (utils/get-extension-value ca-cert utils/subject-key-identifier-oid)]
    (if incoming-key-id
      ;; incoming are byte-arrays, convert to seq for simple comparisons
      (= (seq (:key-identifier incoming-key-id)) (seq ca-key-id))
      false)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public utilities

(schema/defn replace-authority-identifier :- utils/SSLExtensionList
  [extensions  :- utils/SSLExtensionList ca-cert :- X509Certificate]
  (conj (filter #(not= utils/authority-key-identifier-oid (:oid %)) extensions)
        (utils/authority-key-identifier ca-cert)))

(schema/defn replace-subject-identifier :- utils/SSLExtensionList
  [extensions  :- utils/SSLExtensionList subject-public-key :- PublicKey]
  (conj (filter #(not= utils/subject-key-identifier-oid (:oid %)) extensions)
        (utils/subject-key-identifier subject-public-key false)))

(schema/defn update-extensions-for-new-signing :- utils/SSLExtensionList
  [extensions :- utils/SSLExtensionList ca-cert :- X509Certificate subject-public-key :- PublicKey]
  (replace-subject-identifier (replace-authority-identifier extensions ca-cert) subject-public-key))

(schema/defn renew-certificate! :- X509Certificate
  "Given a certificate and CaSettings create a new signed certificate using the public key from the certificate.
  It recreates all the extensions in the original certificate."
  [certificate :- X509Certificate
   {:keys [cacert cakey auto-renewal-cert-ttl] :as ca-settings} :- CaSettings
   report-activity]
  (let [validity (cert-validity-dates (or auto-renewal-cert-ttl default-auto-ttl-renewal-seconds))
        cacert (utils/pem->ca-cert cacert cakey)
        cert-subject (utils/get-subject-from-x509-certificate certificate)
        cert-name (utils/x500-name->CN cert-subject)
        signed-cert (utils/sign-certificate
                      (utils/get-subject-from-x509-certificate cacert)
                      (utils/pem->private-key cakey)
                      (next-serial-number! ca-settings)
                      (:not-before validity)
                      (:not-after validity)
                      cert-subject
                      (.getPublicKey certificate)
                      (update-extensions-for-new-signing
                        (utils/get-extensions certificate)
                        cacert
                        (.getPublicKey certificate)))]
    (write-cert-to-inventory! signed-cert ca-settings)
    (delete-certificate! ca-settings cert-name)
    (log/info (i18n/trs "Renewed certificate for \"{0}\" with new expiration of \"{1}\""
                        cert-name
                        (.format (new SimpleDateFormat "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                                 (.getNotAfter signed-cert))))
    (report-activity [cert-subject] "renewed")
    signed-cert))
