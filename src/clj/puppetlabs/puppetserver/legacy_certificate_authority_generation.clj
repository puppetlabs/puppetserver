(ns puppetlabs.puppetserver.legacy-certificate-authority-generation
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [me.raynes.fs :as fs]
    [puppetlabs.i18n.core :as i18n]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.kitchensink.file :as ks-file]
    [puppetlabs.puppetserver.certificate-authority :as ca]
    [puppetlabs.services.config.certificate-authority-schemas :as opts]
    [puppetlabs.ssl-utils.core :as utils]
    [puppetlabs.puppetserver.common :as common]
    [schema.core :as schema])
  (:import
    (java.nio.file Files)))

;;; The sole public function for this library is `initialize!`.
;;; This functionality is deprecated and so is this library,
;;; it will be removed in a subsequent release of Puppet Server.

(defn required-ca-files
  "The set of SSL related files that are required on the CA."
  [enable-infra-crl]
  (set/union #{:cacert :cacrl :cakey :cert-inventory :serial}
     (if enable-infra-crl #{:infra-nodes-path :infra-crl-path} #{})))

(schema/defn ensure-directories-exist!
  "Create any directories used by the CA if they don't already exist."
  [settings :- opts/CaSettings]
  (doseq [dir [:csrdir :signeddir]]
    (let [path (get settings dir)]
      (when-not (fs/exists? path)
        (ks/mkdirs! path)))))

(schema/defn settings->cadir-paths
  "Trim down the CA settings to include only paths to files and directories.
  These paths are necessary during CA initialization for determining what needs
  to be created and where they should be placed."
  [ca-settings :- opts/CaSettings]
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
                    :auto-renewal-cert-ttl
                    :oid-mappings)]
    (if (:enable-infra-crl ca-settings)
      settings'
      (dissoc settings' :infra-crl-path :infra-node-serials-path))))

(schema/defn ensure-ca-file-perms!
  "Ensure that the CA's private key file has the correct permissions set. If it
  does not, then correct them."
  [settings :- opts/CaSettings]
  (let [ca-p-key (:cakey settings)
        cur-perms (ks-file/get-perms ca-p-key)]
    (when-not (= ca/private-key-perms cur-perms)
      (ks-file/set-perms ca-p-key ca/private-key-perms)
      (log/warn (format "%s %s"
                        (i18n/trs "The private CA key at ''{0}'' was found to have the wrong permissions set as ''{1}''."
                             ca-p-key cur-perms)
                        (i18n/trs "This has been corrected to ''{0}''."
                             ca/private-key-perms))))))

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

(schema/defn create-ca-extensions :- (schema/pred utils/extension-list?)
  "Create a list of extensions to be added to the CA certificate."
  [issuer-public-key :- (schema/pred utils/public-key?)
   ca-public-key :- (schema/pred utils/public-key?)]
  (vec
    (cons (utils/netscape-comment
           ca/netscape-comment-value)
          (utils/create-ca-extensions issuer-public-key ca-public-key))))

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

(schema/defn initialize-serial-file!
  "Initializes the serial number file on disk.  Serial numbers start at 1."
  [{:keys [serial serial-lock serial-lock-timeout-seconds]} :- opts/CaSettings]
  (common/with-safe-write-lock serial-lock ca/serial-lock-descriptor serial-lock-timeout-seconds
    (ks-file/atomic-write-string serial
                                 (ca/format-serial-number 1)
                                 ca/serial-file-permissions)))

(schema/defn generate-ssl-files!
  "Given the CA settings, generate and write to disk all of the necessary
  SSL files for the CA. Any existing files will be replaced."
  [ca-settings :- opts/CaSettings]
  (log/debug (str (i18n/trs "Initializing SSL for the CA; settings:")
                  "\n"
                  (ks/pprint-to-string ca-settings)))
  (ca/create-parent-directories! (vals (settings->cadir-paths ca-settings)))
  (-> ca-settings :csrdir fs/file ks/mkdirs!)
  (-> ca-settings :signeddir fs/file ks/mkdirs!)
  (initialize-serial-file! ca-settings)
  (-> ca-settings :infra-nodes-path fs/file fs/create)
  (ca/generate-infra-serials! ca-settings)
  (let [keypair     (utils/generate-key-pair (:keylength ca-settings))
        public-key  (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)
        x500-name   (utils/cn (:ca-name ca-settings))
        validity    (ca/cert-validity-dates (:ca-ttl ca-settings))
        serial      (ca/next-serial-number! ca-settings)
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
    (ca/write-cert-to-inventory! cacert ca-settings)
    (ca/write-public-key public-key (:capub ca-settings))
    (ca/write-private-key private-key (:cakey ca-settings))
    (ca/write-cert cacert (:cacert ca-settings))
    (ca/write-crl cacrl (:cacrl ca-settings))
    (ca/write-crl infra-crl (:infra-crl-path ca-settings))
    (symlink-cadir (:cadir ca-settings))))

(schema/defn ^:always-validate
  initialize!
  "Given the CA configuration settings, ensure that all
   required SSL files exist. If all files exist,
   new ones will not be generated. If only some are found
   (but others are missing), an exception is thrown."
  [settings :- opts/CaSettings]
  (ensure-directories-exist! settings)
  (let [required-files (-> (settings->cadir-paths settings)
                           (select-keys (required-ca-files (:enable-infra-crl settings)))
                           (vals))]
    (if (every? fs/exists? required-files)
      (do
        (log/info (i18n/trs "CA already initialized for SSL"))
        (when (:enable-infra-crl settings)
          (ca/generate-infra-serials! settings))
        (when (:manage-internal-file-permissions settings)
          (ensure-ca-file-perms! settings)))
      (let [{found true
             missing false} (group-by fs/exists? required-files)]
        (if (= required-files missing)
          (generate-ssl-files! settings)
          (throw (partial-state-error "CA" found missing)))))))
