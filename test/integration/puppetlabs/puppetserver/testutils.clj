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

(def PuppetResource
  "Schema for a Puppet resource. Based on
  https://github.com/puppetlabs/puppet/blob/master/api/schemas/resource_type.json
  which seems a little out of date and incomplete."
  {(schema/required-key "type") schema/Str
   (schema/required-key "title") schema/Str
   (schema/optional-key "name") schema/Str
   (schema/optional-key "tags") [schema/Str]
   (schema/optional-key "exported") schema/Bool
   (schema/optional-key "parameters") {schema/Str schema/Str}
   (schema/optional-key "line") schema/Int
   (schema/optional-key "file") schema/Str
   (schema/optional-key "parent") schema/Str
   (schema/optional-key "doc") schema/Str
   schema/Str schema/Str})

(def PuppetCatalog
  "Schema for a Puppet catalog. Based on
  https://github.com/puppetlabs/puppet/blob/master/api/schemas/catalog.json"
  {(schema/required-key "name") schema/Str
   (schema/required-key "classes") [schema/Str]
   (schema/required-key "environment") schema/Str
   (schema/required-key "version") schema/Int
   (schema/required-key "resources") [PuppetResource]
   (schema/required-key "edges") [{schema/Str schema/Str}]
   (schema/optional-key "code_id") (schema/maybe schema/Str)
   (schema/optional-key "tags") [schema/Str]
   (schema/optional-key "catalog_uuid") schema/Str
   schema/Str schema/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Default settings

(schema/def ^:always-validate conf-dir :- schema/Str
  "./target/master-conf")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Certificates and keys

(schema/defn ^:always-validate pem-file :- schema/Str
  [& args :- [schema/Str]]
  (str (apply fs/file conf-dir "ssl" args)))

(schema/def ^:always-validate ca-cert :- schema/Str
  (pem-file "certs" "ca.pem"))

(schema/def ^:always-validate localhost-cert :- schema/Str
  (pem-file "certs" "localhost.pem"))

(schema/def ^:always-validate localhost-key :- schema/Str
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

(schema/defn ^:always-validate http-get
  [path :- schema/Str]
  (http-client/get
   (str "https://localhost:8140/" path) catalog-request-options))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Interacting with puppet code and catalogs

(schema/defn ^:always-validate write-foo-pp-file :- schema/Str
  ([foo-pp-contents :- schema/Str]
   (write-foo-pp-file foo-pp-contents "init"))
  ([foo-pp-contents :- schema/Str
    pp-name :- schema/Str]
   (write-foo-pp-file foo-pp-contents pp-name "production"))
  ([foo-pp-contents :- schema/Str
    pp-name :- schema/Str
    env-name :- schema/Str]
   (let [foo-pp-file (fs/file conf-dir
                              "environments"
                              env-name
                              "modules"
                              "foo"
                              "manifests"
                              (str pp-name ".pp"))]
     (fs/mkdirs (fs/parent foo-pp-file))
     (spit foo-pp-file foo-pp-contents)
     (.getCanonicalPath foo-pp-file))))

(schema/defn ^:always-validate get-catalog :- PuppetCatalog
  "Make an HTTP get request for a catalog."
  []
  (-> (http-client/get
       "https://localhost:8140/puppet/v3/catalog/localhost?environment=production"
       catalog-request-options)
      :body
      json/parse-string))

(schema/defn ^:always-validate post-catalog :- PuppetCatalog
  "Make an HTTP post request for a catalog."
  []
  (-> (http-client/post
       "https://localhost:8140/puppet/v3/catalog/localhost"
       (assoc-in (assoc catalog-request-options
                   :body "environment=production")
                 [:headers "Content-Type"] "application/x-www-form-urlencoded"))
      :body
      json/parse-string))

(schema/defn ^:always-validate write-site-pp-file
  [site-pp-contents :- schema/Str]
  (let [site-pp-file (fs/file conf-dir "environments" "production" "manifests" "site.pp")]
    (fs/mkdirs (fs/parent site-pp-file))
    (spit site-pp-file site-pp-contents)))

(schema/defn ^:always-validate resource-matches? :- schema/Bool
  [resource-type :- schema/Str
   resource-title :- schema/Str
   resource :- PuppetResource]
  (and (= resource-type (resource "type"))
       (= resource-title (resource "title"))))

(schema/defn ^:always-validate catalog-contains? :- schema/Bool
  [catalog :- PuppetCatalog
   resource-type :- schema/Str
   resource-title :- schema/Str]
  (let [resources (get catalog "resources")]
    (->> resources
        (some (partial resource-matches? resource-type resource-title))
        nil?
        not)))

(schema/defn ^:always-validate num-catalogs-containing :- schema/Int
  [catalogs :- [PuppetCatalog]
   resource-type :- schema/Str
   resource-title :- schema/Str]
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
