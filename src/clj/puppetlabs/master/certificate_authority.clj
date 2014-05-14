(ns puppetlabs.master.certificate-authority
  (:import  [java.io InputStream File IOException]
            [java.security PrivateKey]
            [org.joda.time DateTime Period])
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn path-to-cert
  "Return a path to the `subject`s certificate file under the `certdir`."
  [certdir subject]
  (str certdir "/" subject ".pem"))

(defn path-to-cert-request
  "Return a path to the `subject`s certificate request file under the `csrdir`."
  [csrdir subject]
  (str csrdir "/" subject ".pem"))

(defn calculate-certificate-expiration
  "Return a date-time string for 5 years from now."
  []
  ;; TODO pull the real expiration date off of the certificate request (PE-3173)
  (let [now        (DateTime/now)
        five-years (Period/years 5)
        expiration (.plus now five-years)]
    (str expiration)))

;; TODO persist between runs (PE-3174)
(def serial-number (atom 0))

(defn next-serial-number
  []
  (swap! serial-number inc))

(defn files-exist?
  "Predicate to test whether all of the files exist on disk.
  `paths` is expected to be a map with file path values,
  such as `master-file-paths` or `ca-file-paths`."
  [paths]
  {:pre [(map? paths)]}
  (every? #(fs/exists? %) (vals paths)))

(defn create-parent-directories!
  "Create all intermediate directories present in each of the file paths.
  Throws an exception if the directory cannot be created."
  [paths]
  {:pre [(sequential? paths)]}
  (doseq [path paths]
    (ks/mkdirs! (fs/parent path))))

(defn create-ca-files!
  "Given the paths to all of the CA's SSL files and the name of the CA,
  generate and write to disk all of the necessary SSL files for the CA.
  Any existing files will be replaced."
  [ca-file-paths ca-name keylength]
  {:pre  [(map? ca-file-paths)
          (every? string? (vals ca-file-paths))
          (string? ca-name)
          (integer? keylength)]
   :post [(files-exist? ca-file-paths)]}
  (log/info (str "Initializing SSL for the CA; file paths:\n"
                 (ks/pprint-to-string ca-file-paths)))
  (create-parent-directories! (vals ca-file-paths))
  (let [keypair     (utils/generate-key-pair keylength)
        public-key  (.getPublic keypair)
        private-key (.getPrivate keypair)
        x500-name   (utils/generate-x500-name ca-name)
        cert        (-> (utils/generate-certificate-request keypair x500-name)
                        (utils/sign-certificate-request x500-name (next-serial-number) private-key))]
    ;; create keys
    (utils/key->pem! public-key (:capub ca-file-paths))
    (utils/key->pem! private-key (:cakey ca-file-paths))
    ;; create cert in both places, because that's how the ruby version does it.
    (utils/obj->pem! cert (:cacert ca-file-paths))
    (utils/obj->pem! cert (:localcacert ca-file-paths))
    ;; create CRL
    (-> (.getIssuerX500Principal cert)
        (utils/generate-crl private-key)
        (utils/obj->pem! (:cacrl ca-file-paths)))))

(defn create-master-files!
  "Given the master's SSL file paths & certname, and the CA's name & private key,
  generate and write to disk all of the necessary SSL files for the master.
  Any existing files will be replaced."
  [master-file-paths master-certname ca-name keylength ca-private-key]
  {:pre  [(every? string? (vals master-file-paths))
          (string? master-certname)
          (instance? PrivateKey ca-private-key)
          (string? ca-name)
          (integer? keylength)]
   :post [(files-exist? master-file-paths)]}
  (log/info "Initializing SSL for the Master")
  (create-parent-directories! (vals master-file-paths))
  (let [keypair        (utils/generate-key-pair keylength)
        public-key     (.getPublic keypair)
        private-key    (.getPrivate keypair)
        x500-name      (utils/generate-x500-name master-certname)
        ca-x500-name   (utils/generate-x500-name ca-name)]
    ;; create keys
    (utils/key->pem! public-key (:hostpubkey master-file-paths))
    (utils/key->pem! private-key (:hostprivkey master-file-paths))
    ;; create cert
    (-> (utils/generate-certificate-request keypair x500-name)
        (utils/sign-certificate-request ca-x500-name (next-serial-number) ca-private-key)
        (utils/obj->pem! (:hostcert master-file-paths)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def CaFilePaths
  {:cacert      String
   :cacrl       String
   :cakey       String
   :capub       String
   :localcacert String})

(def MasterFilePaths
  {:hostcert    String
   :hostprivkey String
   :hostpubkey  String})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn get-certificate
  "Given a subject name and paths to the certificate directory and the CA
  certificate, return the subject's certificate as a string, or nil if not found.
  If the subject is 'ca', then use the `cacert` path instead."
  [subject cacert certdir]
  {:pre  [(every? string? [subject cacert certdir])]
   :post [(or (string? %)
              (nil? %))]}
  (let [cert-path (if (= "ca" subject)
                    cacert
                    (path-to-cert certdir subject))]
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
  "Given a subject name, their certificate request, and path to the SSL directory,
  auto-sign the request and write the certificate to disk. Return the certificate
  expiration date as 5 years from now."
  [subject certificate-request cakey ca-name certdir]
  {:pre  [(every? string? [subject cakey ca-name certdir])
          (instance? InputStream certificate-request)]
   :post [(string? %)]}
  (let [request-object  (-> certificate-request
                            utils/pem->objs
                            first)
        ca-private-key  (-> cakey
                            utils/pem->private-key)
        ca-x500-name    (utils/generate-x500-name ca-name)
        next-serial     (next-serial-number)
        cert-path       (path-to-cert certdir subject)
        ;; TODO eventually we don't want to just be autosigning here (PE-3179)
        signed-cert     (utils/sign-certificate-request
                          request-object
                          ca-x500-name
                          next-serial
                          ca-private-key)]
    (utils/obj->pem! signed-cert cert-path))
  (calculate-certificate-expiration))

(defn get-certificate-revocation-list
  "Given the value of the 'cacrl' setting from Puppet,
  return the CRL from the .pem file on disk."
  [cacrl]
  {:pre   [(string? cacrl)]
   :post  [(string? %)]}
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
      (create-ca-files! ca-file-paths ca-name keylength))
    (if (files-exist? master-file-paths)
      (log/info "Master already initialized for SSL")
      (create-master-files! master-file-paths
                            master-certname
                            ca-name
                            keylength
                            (-> ca-file-paths
                                :cakey
                                utils/pem->private-key)))))
