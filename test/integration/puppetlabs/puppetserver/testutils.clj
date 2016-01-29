(ns puppetlabs.puppetserver.testutils
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [cheshire.core :as json]
            [puppetlabs.http.client.sync :as http-client])
  (:import (java.io File)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def PuppetConfFiles
  {schema/Str (schema/pred (some-fn string? #(instance? File %)))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Default settings

(def conf-dir
  "./target/master-conf")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Certificates and keys

(defn pem-file
  [& args]
  (str (apply fs/file conf-dir "ssl" args)))

(def ca-cert
  (pem-file "certs" "ca.pem"))

(def localhost-cert
  (pem-file "certs" "localhost.pem"))

(def localhost-key
  (pem-file "private_keys" "localhost.pem"))

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

(def catalog-request-options
  (merge
   ssl-request-options
   {:headers     {"Accept" "pson"}
    :as          :text}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal functions

(schema/defn ^:always-validate get-with-puppet-conf-target-paths :- PuppetConfFiles
  "Helper function that joins the filename onto the dest-dir for each file in
  puppet-conf-files."
  [puppet-conf-files :- PuppetConfFiles
   dest-dir :- schema/Str]
  (reduce
   (fn [acc filename]
     (assoc acc filename (fs/file dest-dir filename)))
   {}
   (keys puppet-conf-files)))

(schema/defn ^:always-validate copy-with-puppet-conf-files-into-place!
  "Copies each file in puppet-conf-files into the corresponding destination in
  target-paths."
  [puppet-conf-files :- PuppetConfFiles
   target-paths :- PuppetConfFiles]
  (doseq [filename (keys puppet-conf-files)]
    (fs/copy+ (get puppet-conf-files filename)
              (get target-paths filename))))

(schema/defn ^:always-validate cleanup-with-puppet-conf-files!
  "Deletes all of the files in the target-paths map."
  [target-paths :- PuppetConfFiles]
  (doseq [target-path (vals target-paths)]
    (fs/delete target-path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn http-get
  [path]
  (http-client/get
   (str "https://localhost:8140/" path) catalog-request-options))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Interacting with puppet code and catalogs

(defn write-foo-pp-file
  [foo-pp-contents]
  (let [foo-pp-file (fs/file conf-dir
                             "environments"
                             "production"
                             "modules"
                             "foo"
                             "manifests"
                             "init.pp")]
    (fs/mkdirs (fs/parent foo-pp-file))
    (spit foo-pp-file foo-pp-contents)))

(defn get-catalog
  "Make an HTTP get request for a catalog."
  []
  (-> (http-client/get
       "https://localhost:8140/puppet/v3/catalog/localhost?environment=production"
       catalog-request-options)
      :body
      json/parse-string))

(defn post-catalog
  "Make an HTTP post request for a catalog."
  []
  (-> (http-client/post
       "https://localhost:8140/puppet/v3/catalog/localhost"
       (assoc-in (assoc catalog-request-options
                   :body "environment=production")
                 [:headers "Content-Type"] "application/x-www-form-urlencoded"))
      :body
      json/parse-string))

(defn write-site-pp-file
  [site-pp-contents]
  (let [site-pp-file (fs/file conf-dir "environments" "production" "manifests" "site.pp")]
    (fs/mkdirs (fs/parent site-pp-file))
    (spit site-pp-file site-pp-contents)))

(defn resource-matches?
  [resource-type resource-title resource]
  (and (= resource-type (resource "type"))
       (= resource-title (resource "title"))))

(defn catalog-contains?
  [catalog resource-type resource-title]
  (let [resources (get catalog "resources")]
    (some (partial resource-matches? resource-type resource-title) resources)))

(defn num-catalogs-containing
  [catalogs resource-type resource-title]
  (count (filter #(catalog-contains? % resource-type resource-title) catalogs)))

(defmacro with-puppet-conf-files
  "A macro that can be used to wrap a single test with the setup and teardown
  of with-puppet-conf.

  Accepts a map with the following characteristics:

  * keys are simple filenames (no paths) that will be created in puppet's $confdir
  * values are paths to a test-specific file that should be copied to puppet's confdir,
    a la `$confdir/<key>`.

  For each entry in the map, copies the files into $confdir.
  Then it runs the body.
  Then after running the body it deletes all of the files from $confdir.

  Optionally accepts a second argument `dest-dir`, which specifies the location
  of Puppet's $confdir.  If this argument is not provided, defaults to
  `jruby-testutils/conf-dir`."
  [puppet-conf-files dest-dir & body]
  `(let [target-paths# (get-with-puppet-conf-target-paths ~puppet-conf-files ~dest-dir)]
     (copy-with-puppet-conf-files-into-place! ~puppet-conf-files target-paths#)
     (try
       ~@body
       (finally
         (cleanup-with-puppet-conf-files! target-paths#)))))

(schema/defn ^:always-validate with-puppet-conf-fixture
  "Test fixture; Accepts a map with the following characteristics:

  * keys are simple filenames (no paths) that will be created in puppet's $confdir
  * values are paths to a test-specific file that should be copied to puppet's confdir,
    a la `$confdir/<key>`.

  For each entry in the map, copies the files into $confdir, runs the test function,
  and then deletes all of the files from $confdir.

  Optionally accepts a second argument `dest-dir`, which specifies the location
  of Puppet's $confdir.  If this argument is not provided, defaults to
  `jruby-testutils/conf-dir`."
  ([puppet-conf-files]
   (with-puppet-conf-fixture puppet-conf-files conf-dir))
  ([puppet-conf-files :- PuppetConfFiles
    dest-dir :- schema/Str]
   (fn [f]
     (with-puppet-conf-files puppet-conf-files dest-dir (f)))))

(defn with-puppet-conf
  "This function returns a test fixture that will copy a specified puppet.conf
  file into the provided location for testing, and then delete it after the
  tests have completed. If no destination dir is provided then the puppet.conf
  file is copied to the default location of './target/master-conf'."
  ([puppet-conf-file]
   (with-puppet-conf puppet-conf-file conf-dir))
  ([puppet-conf-file dest-dir]
   (with-puppet-conf-fixture {"puppet.conf" puppet-conf-file} dest-dir)))
