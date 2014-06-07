(ns puppetlabs.master.certificate-authority
  (:import  [java.io InputStream]
            [org.joda.time DateTime Period])
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def CaFilePaths
  "Paths to the various directories and files within the CA directory.
  These are used during initialization of the CA, and may not be necessary
  thereafter. All of these are Puppet configuration settings"
  {:cacrl     String
   :cacert    String
   :cakey     String
   :capub     String
   :csrdir    String
   :signeddir String})

(def MasterFilePaths
  "Paths to the various directories and files within the SSL directory,
  excluding the CA directory and its contents (see `CaFilePaths`).
  These are only used during initialization of the master.
  All of these are Puppet configuration settings."
  {:requestdir  String
   :certdir     String
   :hostcert    String
   :localcacert String
   :hostprivkey String
   :hostpubkey  String})

(def CaSettings
  "Settings from Puppet that are used through the lifetime of the CA.
  Some of these may be necessary during initialization as well, so there
  may be some overlap between this and `CaFilePaths`.
  All of these are Puppet configuration settings."
  {:cacert    String
   :cacrl     String
   :cakey     String
   :ca-name   String
   :ca-ttl    schema/Int
   :csrdir    String
   :signeddir String})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn path-to-cert
  "Return a path to the `subject`s certificate file under the `signeddir`."
  [signeddir subject]
  (str signeddir "/" subject ".pem"))

(defn path-to-cert-request
  "Return a path to the `subject`s certificate request file under the `csrdir`."
  [csrdir subject]
  (str csrdir "/" subject ".pem"))

(defn calculate-certificate-expiration
  "Calculate the cert's expiration date based on the value of Puppet's 'ca_ttl'
   setting"
  [ca-ttl]
  {:pre   [(integer? ca-ttl)]
   :post  [(instance? DateTime %)]}
  ;; TODO - PE-3173 - calculate the expiration date based off of the issue date of the CSR
  (let [now (DateTime/now)
        ttl (Period/seconds ca-ttl)]
    (.plus now ttl)))

;; TODO persist between runs (PE-3174)
(def serial-number (atom 0))

(defn next-serial-number
  []
  (swap! serial-number inc))

(defn files-exist?
  "Predicate to test whether all of the files exist on disk.
  `paths` is expected to be a map with file path values,
  such as `ssldir-file-paths` or `cadir-file-paths`."
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

(defn initialize-ca!
  "Given the CA directory file paths and CA name, generate and
  write to disk all of the necessary SSL files for the CA.
  Any existing files will be replaced."
  [cadir-file-paths ca-name keylength]
  {:pre  [(map? cadir-file-paths)
          (every? string? (vals cadir-file-paths))
          (string? ca-name)
          (integer? keylength)]
   :post [(files-exist? cadir-file-paths)]}
  (log/info (str "Initializing SSL for the CA; file paths:\n"
                 (ks/pprint-to-string cadir-file-paths)))
  (create-parent-directories! (vals cadir-file-paths))
  (-> cadir-file-paths :csrdir fs/file ks/mkdirs!)
  (-> cadir-file-paths :signeddir fs/file ks/mkdirs!)
  (let [keypair     (utils/generate-key-pair keylength)
        public-key  (.getPublic keypair)
        private-key (.getPrivate keypair)
        x500-name   (utils/generate-x500-name ca-name)
        cacert      (-> (utils/generate-certificate-request keypair x500-name)
                        (utils/sign-certificate-request x500-name
                                                        (next-serial-number)
                                                        private-key))
        cacrl       (-> cacert
                        .getIssuerX500Principal
                        (utils/generate-crl private-key))]
    (utils/key->pem! public-key (:capub cadir-file-paths))
    (utils/key->pem! private-key (:cakey cadir-file-paths))
    (utils/obj->pem! cacert (:cacert cadir-file-paths))
    (utils/obj->pem! cacrl (:cacrl cadir-file-paths))))

(defn initialize-master!
  "Given the SSL directory file paths, master certname, and CA information,
  generate and write to disk all of the necessary SSL files for the master.
  Any existing files will be replaced."
  [ssldir-file-paths master-certname ca-name ca-private-key ca-cert keylength]
  {:pre  [(every? string? (vals ssldir-file-paths))
          (string? master-certname)
          (string? ca-name)
          (utils/private-key? ca-private-key)
          (utils/certificate? ca-cert)
          (integer? keylength)]
   :post [(files-exist? ssldir-file-paths)]}
  (log/info (str "Initializing SSL for the Master; file paths:\n"
                 (ks/pprint-to-string ssldir-file-paths)))
  (create-parent-directories! (vals ssldir-file-paths))
  (-> ssldir-file-paths :requestdir fs/file ks/mkdirs!)
  (let [keypair      (utils/generate-key-pair keylength)
        public-key   (.getPublic keypair)
        private-key  (.getPrivate keypair)
        x500-name    (utils/generate-x500-name master-certname)
        ca-x500-name (utils/generate-x500-name ca-name)
        hostcert     (-> (utils/generate-certificate-request keypair x500-name)
                         (utils/sign-certificate-request ca-x500-name
                                                         (next-serial-number)
                                                         ca-private-key))]
    (utils/key->pem! public-key (:hostpubkey ssldir-file-paths))
    (utils/key->pem! private-key (:hostprivkey ssldir-file-paths))
    (utils/obj->pem! hostcert (:hostcert ssldir-file-paths))
    (utils/obj->pem! ca-cert (:localcacert ssldir-file-paths))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn get-certificate
  "Given a subject name and paths to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject cacert signeddir]
  {:pre  [(every? string? [subject cacert signeddir])]
   :post [(or (string? %)
              (nil? %))]}
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert signeddir subject))]
    (if (fs/exists? cert-path)
      (slurp cert-path))))

(defn get-certificate-request
  "Given a subject name, return their certificate request as a string, or nil if
  not found.  Looks for certificate requests in `csrdir`."
  [subject csrdir]
  {:pre  [(every? string? [subject csrdir])]
   :post [(or (string? %)
              (nil? %))]}
  (let [cert-request-path (path-to-cert-request csrdir subject)]
    (if (fs/exists? cert-request-path)
      (slurp cert-request-path))))

(defn autosign-certificate-request!
  "Given a subject name, their certificate request, and the CA settings
  from Puppet, auto-sign the request and write the certificate to disk.
  Return the certificate expiration date."
  [subject certificate-request {:keys [ca-name cakey signeddir ca-ttl]}]
  {:pre  [(string? subject)
          (instance? InputStream certificate-request)]
   :post [(instance? DateTime %)]}
  (let [request-object  (-> certificate-request
                            utils/pem->objs
                            first)
        ca-private-key  (utils/pem->private-key cakey)
        ca-x500-name    (utils/generate-x500-name ca-name)
        cert-path       (path-to-cert signeddir subject)
        signed-cert     (utils/sign-certificate-request
                          request-object
                          ca-x500-name
                          (next-serial-number)
                          ca-private-key)]
    (utils/obj->pem! signed-cert cert-path))
  (calculate-certificate-expiration ca-ttl))

(defn get-certificate-revocation-list
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl]
  {:pre  [(string? cacrl)]
   :post [(string? %)]}
  (slurp cacrl))

(schema/defn ^:always-validate
  initialize!
  "Given the CA file paths, master file paths, the master's certname,
  and the CA's name, prepare all necessary SSL files for the master and CA.
  If all of the necessary SSL files exist, new ones will not be generated."
  ([ca-file-paths master-file-paths master-certname ca-name]
    (initialize! ca-file-paths
                 master-file-paths
                 master-certname
                 ca-name
                 utils/default-key-length))
  ([ca-file-paths     :- CaFilePaths
    master-file-paths :- MasterFilePaths
    master-certname   :- String
    ca-name           :- String
    keylength         :- schema/Int]
    (if (files-exist? ca-file-paths)
      (log/info "CA already initialized for SSL")
      (initialize-ca! ca-file-paths ca-name keylength))
    (if (files-exist? master-file-paths)
      (log/info "Master already initialized for SSL")
      (let [cakey  (-> ca-file-paths :cakey utils/pem->private-key)
            cacert (-> ca-file-paths :cacert utils/pem->certs first)]
        (initialize-master! master-file-paths master-certname
                            ca-name cakey cacert keylength)))))
