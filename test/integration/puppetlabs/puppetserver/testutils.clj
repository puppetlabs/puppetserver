(ns puppetlabs.puppetserver.testutils
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [cheshire.core :as json]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.ringutils :as ringutils])
  (:import (java.io File)
           (java.net URL)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def PuppetConfFiles
  {schema/Str (schema/pred (some-fn string? #(instance? File %) #(instance? URL %)))})

(def PuppetResource
  "Schema for a Puppet resource. Based on the resource within the catalog schema."
  {(schema/required-key "type") schema/Str
   (schema/required-key "title") schema/Str
   (schema/optional-key "line") schema/Int
   (schema/optional-key "file") schema/Str
   (schema/required-key "exported") schema/Bool
   (schema/optional-key "sensitive_parameters") [schema/Str]
   (schema/required-key "tags") [schema/Str]
   (schema/optional-key "parameters") {schema/Str schema/Str}
   (schema/optional-key "ext_parameters") {schema/Str schema/Str}
   schema/Str schema/Str})

(def PuppetCatalog
  "Schema for a Puppet catalog. Based on
  https://github.com/puppetlabs/puppet/blob/stable/api/schemas/catalog.json"
  {(schema/required-key "name") schema/Str
   (schema/required-key "classes") [schema/Str]
   (schema/required-key "environment") schema/Str
   (schema/required-key "version") schema/Any
   (schema/required-key "resources") [PuppetResource]
   (schema/required-key "edges") [{schema/Str schema/Str}]
   (schema/optional-key "code_id") (schema/maybe schema/Str)
   (schema/optional-key "tags") [schema/Str]
   (schema/optional-key "catalog_uuid") schema/Str
   (schema/optional-key "catalog_format") schema/Int
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
   {:headers     {"Accept" "application/json"}
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
    (let [dest (io/file (get target-paths filename))
          dest-dir (.getParentFile dest)]
      (when dest-dir
        (ks/mkdirs! dest-dir))
      (with-open [source (io/input-stream (get puppet-conf-files filename))]
        (io/copy source dest)))))

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

(defn create-file
  [file content]
  (ks/mkdirs! (fs/parent file))
  (spit file content))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Interacting with puppet code and catalogs

(schema/defn ^:always-validate module-dir :- File
  [env-name :- schema/Str
   module-name :- schema/Str]
  (fs/file conf-dir "environments" env-name "modules" module-name))

(schema/defn ^:always-validate create-module :- schema/Str
  [module-name :- schema/Str
   options :- {(schema/optional-key :env-name) schema/Str
               (schema/optional-key :module-version) schema/Str}]
  (let [default-options {:env-name "production"
                         :module-version "1.0.0"}
        options (merge default-options options)
        module-dir (module-dir (:env-name options) module-name)
        metadata-json {"name" module-name
                       "version" (:module-version options)
                       "author" "Puppet"
                       "license" "apache"
                       "dependencies" []
                       "source" "https://github.com/puppetlabs"}]
    (fs/mkdirs module-dir)
    (spit (fs/file module-dir "metadata.json")
          (json/generate-string metadata-json))
    (.getCanonicalPath module-dir)))

(schema/defn ^:always-validate write-pp-file :- schema/Str
  ([pp-contents :- schema/Str
    module-name :- schema/Str]
   (write-pp-file pp-contents module-name "init"))
  ([pp-contents :- schema/Str
    module-name :- schema/Str
    pp-name :- schema/Str]
   (write-pp-file pp-contents module-name pp-name "production"))
  ([pp-contents :- schema/Str
    module-name :- schema/Str
    pp-name :- schema/Str
    env-name :- schema/Str]
   (write-pp-file pp-contents module-name pp-name env-name "1.0.0"))
  ([pp-contents :- schema/Str
    module-name :- schema/Str
    pp-name :- schema/Str
    env-name :- schema/Str
    module-version :- schema/Str]
   (let [pp-file (fs/file (module-dir env-name module-name)
                          "manifests"
                          (str pp-name ".pp"))]
     (create-module module-name {:env-name env-name :module-version module-version})
     (fs/mkdirs (fs/parent pp-file))
     (spit pp-file pp-contents)
     (.getCanonicalPath pp-file))))

(schema/defn ^:always-validate write-foo-pp-file :- schema/Str
  [foo-pp-contents]
  (write-pp-file foo-pp-contents "foo"))

(schema/defn ^:always-validate write-tasks-files :- schema/Str
  ([module-name :- schema/Str
    task-name :- schema/Str
    task-file-contents :- schema/Str
    task-metadata :- schema/Any]
   (let [module-dir (create-module module-name {})
         tasks-dir (fs/file module-dir "tasks")
         metadata-file-path (fs/file tasks-dir (str task-name ".json"))]
     (create-file metadata-file-path task-metadata)
     (create-file (fs/file tasks-dir (str task-name ".sh"))
                  task-file-contents)
     (.getCanonicalPath metadata-file-path)))
  ([module-name :- schema/Str
    task-name :- schema/Str
    task-file-contents :- schema/Str]
   (write-tasks-files module-name task-name task-file-contents
                      (json/encode {"description" "This is a description. It describes a thing."}))))

(defn create-env-conf
  [env-dir content]
  (create-file (fs/file env-dir "environment.conf")
               (str "environment_timeout = unlimited\n"
                    content)))

(schema/defn ^:always-validate get-static-file-content
  ([url-end :- schema/Str]
   (get-static-file-content url-end true))
  ([url-end :- schema/Str
    include-ssl-certs? :- schema/Bool]
   (let [request-options (if include-ssl-certs?
                           catalog-request-options
                           {:ssl-ca-cert ca-cert
                            :headers     {"Accept" "application/json"}})]
     (http-client/get (str "https://localhost:8140/puppet/v3/static_file_content/" url-end)
                      (assoc request-options
                        :as :text)))))

(schema/defn ^:always-validate catalog-ring-response->catalog :- PuppetCatalog
  "Convert a response from a catalog request into a catalog map"
  [response :- ringutils/RingResponse]
  (-> response :body json/parse-string))

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
  ([site-pp-contents :- schema/Str]
   (write-site-pp-file (fs/file conf-dir "environments" "production") site-pp-contents))
  ([base-dir :- File
    site-pp-contents :- schema/Str]
   (let [site-pp-file (fs/file base-dir "manifests" "site.pp")]
     (fs/mkdirs (fs/parent site-pp-file))
     (spit site-pp-file site-pp-contents))))

(schema/defn ^:always-validate catalog-name-matches? :- schema/Bool
  "Return whether the name (host) found in the catalog matches the supplied
  name parameter."
  [catalog :- PuppetCatalog
   name :- schema/Str]
  (= name (get catalog "name")))

(schema/defn ^:always-validate resource-matches? :- schema/Bool
  ([resource-type :- schema/Str
    resource-title :- schema/Str
    resource :- PuppetResource]
   (and (= resource-type (resource "type"))
        (= resource-title (resource "title"))))
  ([resource-type :- schema/Str
    resource-title :- schema/Str
    resource :- PuppetResource
    param-name :- schema/Str
    param-value :- schema/Any]
   (and
    (resource-matches? resource-type resource-title resource)
    (= param-value (get-in resource ["parameters" param-name])))))

(schema/defn ^:always-validate catalog-contains? :- schema/Bool
  ([catalog :- PuppetCatalog
    resource-type :- schema/Str
    resource-title :- schema/Str]
   (let [resources (get catalog "resources")]
     (->> resources
          (some (partial resource-matches? resource-type resource-title))
          nil?
          not)))
  ([catalog :- PuppetCatalog
    resource-type :- schema/Str
    resource-title :- schema/Str
    param-name :- schema/Str
    param-value :- schema/Any]
   (let [resources (get catalog "resources")]
     (some #(resource-matches? resource-type resource-title % param-name param-value) resources))))

(schema/defn catalog-code-id :- (schema/maybe schema/Str)
  "Given a catalog, returns the code_id from the catalog."
  [catalog :- PuppetCatalog]
  (get catalog "code_id"))

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

(defn copy-config-files
  [dirs-to-copy]
  (doseq [[src target] dirs-to-copy]
    (fs/mkdirs target)
    (let [[_ dirs files] (first (fs/iterate-dir src))]
      (doseq [d dirs] (fs/copy-dir (fs/file src d) target))
      (doseq [f files] (fs/copy (fs/file src f) (fs/file target f))))))

(defn remove-config-files
  [dirs-to-copy]
  (doseq [[src target] dirs-to-copy]
    (let [[_ dirs files] (first (fs/iterate-dir src))]
      (doseq [d dirs] (fs/delete-dir (fs/file target d)))
      (doseq [f files] (fs/delete (fs/file target f))))))

(defmacro with-config-dirs
  "Evaluates the supplied body after copying the source directory (key) to
  target directory (value) for each element in the supplied dirs-to-copy
  map.  After the body is evaluated, all of the copied directory content is
  removed.

  For example:

  (with-config-dirs
     {\"/my/source-dir1\" \"/my/target-dir1\"
      \"/my/source-dir2\" \"/my/target-dir2\"}
      (println \"Do something interesting\"))"
  [dirs-to-copy & body]
  `(do
     (copy-config-files ~dirs-to-copy)
     (try
       ~@body
       (finally
         (remove-config-files ~dirs-to-copy)))))
