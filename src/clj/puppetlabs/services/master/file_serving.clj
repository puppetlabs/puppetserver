(ns puppetlabs.services.master.file-serving
  (:require [bidi.bidi :as bidi]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [digest :as digest]
            [me.raynes.fs :as fs]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetserver.common :as common]
            [puppetlabs.ring-middleware.utils :as middleware-utils]
            [ring.util.response :as rr])
  (:import com.sun.security.auth.module.UnixSystem
           [java.nio.file Files FileSystems FileVisitOption FileVisitResult LinkOption Paths SimpleFileVisitor]))

(def follow-links
  (into-array LinkOption []))

(defn as-path
  [string & more]
  (Paths/get string (into-array String more)))

(defn path-exists?
  [path]
  (Files/exists path follow-links))

(defn get-checksum
  [file algorithm]
  (case algorithm
    "md5" (str "{md5}" (digest/md5 (io/file file)))
    "sha256" (str "{sha256}" (digest/sha-256 (io/file file)))
    :default (throw (Exception. "Unsupported digest"))))

(defn read-attributes
  [path checksum-type ignore-source-permissions]
  (let [attributes (Files/readAttributes path "unix:*" follow-links)]
    {:owner (if ignore-source-permissions
              (.getUid (UnixSystem.))
              (get attributes "uid"))
     :group (if ignore-source-permissions
              (.getGid (UnixSystem.))
              (get attributes "gid"))
     :mode (if ignore-source-permissions
             0644  ;; Yes, Puppet even sends 644 for directories in this case
             (bit-and (get attributes "mode") 07777))
     :type (if (get attributes "isDirectory")
             "directory"
             "file")
     :checksum (if (get attributes "isDirectory")
                 {:type "ctime"
                  :value (str "{ctime}" (get attributes "ctime"))}
                 {:type checksum-type
                  :value (get-checksum path checksum-type)})}))

(defn get-file-metadata
  [checksum-type ignore-source-permissions [relative-path root]]
  (merge (read-attributes (.resolve root relative-path) checksum-type ignore-source-permissions)
         {:path (.toString root)
          :relative_path (.toString relative-path)
          :links "follow"
          :destination nil}))

(defn relativize
  "A version of java's Path#relativize that matches puppet's behavior"
  [root path]
  (let [relative-path (.relativize root path)]
    (if (= (.toString relative-path) "")
      (as-path ".")
      relative-path)))

(defn make-visitor
  [tree root ignores]
  (let [filesystem (FileSystems/getDefault)
        matchers (map #(.getPathMatcher filesystem (str "glob:" %)) ignores)
        ignore? (fn [path]
                  (let [file-name (.getFileName path)]
                   (some #(.matches % file-name) matchers)))]
    (proxy [SimpleFileVisitor] []
     (preVisitDirectory [path attributes]
       (if (ignore? path)
         FileVisitResult/SKIP_SUBTREE
         (do
           (swap! tree assoc (relativize root path) root)
           FileVisitResult/CONTINUE)))
     (visitFile [path attributes]
       (when-not (ignore? path)
         (swap! tree assoc (relativize root path) root))
       FileVisitResult/CONTINUE))))

(defn walk-directory
  [path ignores]
  (let [results (atom {})
        absolute-path (.toAbsolutePath path)
        visitor (make-visitor results absolute-path ignores)]
    (Files/walkFileTree absolute-path #{FileVisitOption/FOLLOW_LINKS} Integer/MAX_VALUE visitor)
    @results))

(defn is-bolt-project?
  [dir]
  (or (fs/exists? (str dir "/bolt-project.yaml"))
      (fs/exists? (str dir "/bolt.yaml"))
      (fs/exists? (str dir "/Boltdir"))))

(defn get-project-root
  "Lookup a project by name and return the path where its project files are
  located as a string."
  [projects-dir versioned-project]
  (let [boltdir-path (str projects-dir "/" versioned-project "/Boltdir")]
    (if (fs/exists? boltdir-path)
      boltdir-path
      (str projects-dir "/" versioned-project))))

(defn list-dirs-in-paths
  "Given a list of directories, return a list of all the subdirectories
  concatenated, preserving the order of the original list. Directories that
  don't exist will be ignored."
  [paths]
  (->> paths
       (filter fs/exists?)
       (mapcat (comp sort fs/list-dir))
       (filter fs/directory?)))

(defn dirs-in-project-modulepath
  "List all directories in a bolt project's modulepath. Returns a sequence of
  File objects."
  [modulepath project-root]
  (->> modulepath
       (map #(str project-root "/" %))
       list-dirs-in-paths))

(defn find-project-module
  "Given the path of a project, the name of a module, and the modulepath defined
  in the project's config (can be nil) search the project's module path and
  return the path to that module as a File, or nil."
  [bolt-builtin-content-dir project-root module modulepath]
  (->> bolt-builtin-content-dir
       list-dirs-in-paths
       (concat (dirs-in-project-modulepath modulepath project-root))
       (filter #(= module (fs/base-name %)))
       first))

(defn mount->path-component
  "Map the fileserving \"mount\" to the subdirectory of modules it will serve
  files out of."
  [mount]
  (case mount
    "modules" "files"
    ;; default to itself
    mount))

(defn read-bolt-project-config
  [project-dir]
  (let [config-path (str project-dir "/bolt-project.yaml")]
    (when (fs/file? config-path)
      (common/parse-yaml (slurp config-path)))))

(defn parse-modulepath
  "The modulepath for a bolt project can either be an array of paths or a string
  with a path separator."
  [modulepath]
  (if (string? modulepath)
    (str/split modulepath #":")
    modulepath))

(defn get-project-modulepath
  "Given a map representing the bolt project configuration, return the
  modulepath. If the modulepath is not defined or the configuration map passed
  is nil then return the default modulepath."
  [project-config]
  ;; Note that the :modulepath config key's useful when the project
  ;; organizes its modules inside relative directories. It doesn't
  ;; make sense for absolute paths since those may not exist
  ;; on the Puppetserver host.
  (-> (get project-config :modulepath "modules")
      (parse-modulepath)
      (concat [".modules"])))

(defn find-project-file
  "Find a file in a project using the parameters from a `file_content` request.
  Returns the path as as string. If the module name is the same as the project
  name then files are served from the project directly."
  [bolt-builtin-content-dir bolt-projects-dir versioned-project mount module path]
  (when (is-bolt-project? (str bolt-projects-dir "/" versioned-project))
    (let [project-root (get-project-root bolt-projects-dir versioned-project)
          project-config (read-bolt-project-config project-root)
          project-name (get project-config :name)
          modulepath (get-project-modulepath project-config)
          module-root (if (= project-name module)
                        project-root
                        (find-project-module bolt-builtin-content-dir project-root module modulepath))
          file-path (str module-root "/" (mount->path-component mount) "/" path)]
      (when (fs/exists? file-path)
        file-path))))

(defn mount-dirs-in-modulepath
  "Collect all the paths represented by a specific mount that are found in the
  modulepath. If `project-as-module?` is truthy then include any mount
  directory found at the top level of the project."
  [bolt-builtin-content-dir mount modulepath project-root project-as-module?]
  (let [sub-dir (case mount
                    "plugins" "lib"
                    "pluginfacts" "facts.d")
        modules-in-modulepath (concat
                                (dirs-in-project-modulepath modulepath project-root)
                                (list-dirs-in-paths bolt-builtin-content-dir))
        module-dirs (if project-as-module?
                      (cons (fs/file project-root) modules-in-modulepath)
                      modules-in-modulepath)]
    (->> module-dirs
         (map #(.resolve (.toPath %) sub-dir))
         (filter path-exists?))))

(defn project-configured-as-module?
  "Given a project config, return whether it is configured to use the project
  itself as a module. Users opt in to this by including a project name setting
  in the configuration file. "
  [project-config]
  (boolean (get project-config :name)))

(defn plugin-file-if-exists
  "Given a relative path as received by the plugins mount, and the path of a lib
  dir in a module, return the path of the file if it exists in the lib dir."
  [relative-path lib-root]
  (let [file-path (.resolve lib-root relative-path)]
    (when (path-exists? file-path)
      (.toString file-path))))

(defn find-project-plugin-file
  "Given a relative path as received by the plugins mount, search all lib dirs
  and return the path to the file if it's found in any of them."
  [bolt-builtin-content-dir bolt-projects-dir versioned-project mount relative-path]
  (let [project-root (get-project-root bolt-projects-dir versioned-project)
        project-config (read-bolt-project-config project-root)
        project-as-module? (project-configured-as-module? project-config)
        modulepath (get-project-modulepath project-config)
        mount-dirs (mount-dirs-in-modulepath bolt-builtin-content-dir mount modulepath project-root project-as-module?)]
    (some (partial plugin-file-if-exists relative-path) mount-dirs)))

(defn get-plugins-metadata
  "Return the metadata for pluginsync. This scans the lib directories of all
  modules and returns a list of files smashed together."
  [bolt-builtin-content-dir bolt-projects-dir versioned-project mount checksum-type ignores ignore-source-permissions]
  (when (is-bolt-project? (str bolt-projects-dir "/" versioned-project))
    (let [project-root (get-project-root bolt-projects-dir versioned-project)
          project-config (read-bolt-project-config project-root)
          project-as-module? (project-configured-as-module? project-config)
          modulepath (get-project-modulepath project-config)
          files (->> (mount-dirs-in-modulepath bolt-builtin-content-dir mount modulepath project-root project-as-module?)
                     (map #(walk-directory % ignores))
                     reverse
                     (apply merge))]
      (map (partial get-file-metadata checksum-type ignore-source-permissions) files))))

(def project-routes
  "Bidi routing table for project file_content endpoint. This is done separately
  because we have to do additional routing after branching on the query
  parameter, which is not natively supported in bidi."

  ["" {[[#"modules|scripts|tasks" :mount-point] "/" :module "/" [#".+" :file-path]] :basic
       [[#"plugins|pluginfacts" :mount-point] #"/?" [#".*" :file-path]] :pluginsync}])

(defn make-file-content-response
  "Given a path to a file, generate an appropriate ring response map. Returns a
  404 response if passed `nil`."
  [file requested-file-path]
  (if file
    (-> file
        rr/file-response
        (rr/content-type "application/octet-stream"))
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body (i18n/tru "Could not find file_content for path: {0}" requested-file-path)}))

(defn handle-project-file-content
  "Handle a file_content request for a bolt project."
  [bolt-builtin-content-dir bolt-projects-dir request]
  (let [versioned-project (get-in request [:params "versioned_project"])
        path (get-in request [:params :rest])
        match (bidi/match-route project-routes path)]
    (when match
      (let [mount-point (get-in match [:route-params :mount-point])
            mount-type (get-in match [:handler])
            module (get-in match [:route-params :module])
            file-path (get-in match [:route-params :file-path])]
        (case mount-type
          :basic (make-file-content-response
                  (find-project-file bolt-builtin-content-dir bolt-projects-dir versioned-project mount-point module file-path)
                  file-path)
          :pluginsync (make-file-content-response
                       (find-project-plugin-file bolt-builtin-content-dir bolt-projects-dir versioned-project mount-point file-path)
                       file-path)
          {:status 400
           :headers {"Content-Type" "text/plain"}
           :body (i18n/tru "Unsupported mount: {0}" mount-point)})))))

(defn file-content-handler
  "Handle file_content requests and dispatch them to the correct handler for
  environments or projects."
  [bolt-builtin-content-dir bolt-projects-dir ruby-request-handler request]
  (let [versioned-project (get-in request [:params "versioned_project"])
        environment (get-in request [:params "environment"])]
    (cond
      (and versioned-project environment)
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (i18n/tru "A file_content request cannot specify both `environment` and `versioned_project` query parameters.")}
      (and (nil? versioned-project) (nil? environment))
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (i18n/tru "A file_content request must include an `environment` or `versioned_project` query parameter.")}
      versioned-project
      (handle-project-file-content bolt-builtin-content-dir bolt-projects-dir request)

      :else
      (ruby-request-handler request))))

(defn metadatas-params-errors
  "Reject requests with parameter values that we haven't implemented yet.
  Returns a list of errors."
  [params]
  (->>
   (for [[value default] {"recurse" "true"
                          "links" "follow"
                          "source_permissions" "ignore"
                          "recurselimit" "infinite"}]
     (when (and (get params value)
                (not (= value default)))
       (str "The only supported value of `" value "` at this time is `" default "`")))
   (filter (comp not nil?))))

(defn handle-project-file-metadatas
  "Handle a file_metadatas request for a bolt project."
  [bolt-builtin-content-dir bolt-projects-dir request]
  (let [versioned-project (get-in request [:params "versioned_project"])
        path (get-in request [:params :rest])
        match (bidi/match-route project-routes path)]
    (when match
      (let [mount-point (get-in match [:route-params :mount-point])
            mount-type (get-in match [:handler])
            ignore (get-in request [:params "ignore"] [])
            checksum-type (get-in request [:params "checksum_type"] "sha256")]
        (case mount-type
          :pluginsync (let [errors (metadatas-params-errors (get-in request [:params]))]
                        (if (empty? errors)
                          (middleware-utils/json-response
                           200
                           (get-plugins-metadata bolt-builtin-content-dir bolt-projects-dir versioned-project mount-point checksum-type ignore true))
                          {:status 400
                           :headers {"Content-Type" "text/plain"}
                           :body (str/join "\n" (cons "Not all parameter values are supported in this implementation: " errors))}))
          {:status 400
           :headers {"Content-Type" "text/plain"}
           :body (i18n/tru "Unsupported mount: {0}" mount-point)})))))

(defn file-metadatas-handler
  "Handle file_metadatas requests and dispatch them to the correct handler for
  environments or projects."
  [bolt-builtin-content-dir bolt-projects-dir ruby-request-handler request]
  (let [versioned-project (get-in request [:params "versioned_project"])
        environment (get-in request [:params "environment"])]
    (cond
      (and versioned-project environment)
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (i18n/tru "A file_metadatas request cannot specify both `environment` and `versioned_project` query parameters.")}
      (and (nil? versioned-project) (nil? environment))
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (i18n/tru "A file_metadatas request must include an `environment` or `versioned_project` query parameter.")}
      versioned-project
      (handle-project-file-metadatas bolt-builtin-content-dir bolt-projects-dir request)

      :else
      (ruby-request-handler request))))
