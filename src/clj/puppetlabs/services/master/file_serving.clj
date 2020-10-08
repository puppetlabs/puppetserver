(ns puppetlabs.services.master.file-serving
  (:require [bidi.bidi :as bidi]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [puppetlabs.i18n.core :as i18n]
            [ring.util.response :as rr]))

(defn is-bolt-project?
  [dir]
  (or (fs/exists? (str dir "/bolt-project.yaml"))
      (fs/exists? (str dir "/bolt.yaml"))
      (fs/exists? (str dir "/Boltdir"))))

(defn get-project-root
  "Lookup a project by name and return the path where its project files are
  located as a string."
  [projects-dir project-name]
  (let [boltdir-path (str projects-dir "/" project-name "/Boltdir")]
    (if (fs/exists? boltdir-path)
      boltdir-path
      (str projects-dir "/" project-name))))

(defn list-dirs-in-paths
  "Given a list of directories, return a list of all the subdirectories
  concatenated, preserving the order of the original list. Directories that
  don't exist will be ignored."
  [paths]
  (->> paths
       (filter fs/exists?)
       (mapcat fs/list-dir)
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
  [project-root module modulepath]
  (->> project-root
       (dirs-in-project-modulepath modulepath)
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
    (if (fs/file? config-path)
     (yaml/parse-string (slurp config-path)))))

(def default-project-modulepath
  ["modules" "site-modules" "site"])

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
  (let [has-modules-key? (contains? project-config :modules)]
    (if-let [modulepath (parse-modulepath (get project-config :modulepath))]
      (if has-modules-key?
        (concat modulepath [".modules"])
        modulepath)
      (if has-modules-key?
        ["modules" ".modules"]
        default-project-modulepath))))

(defn find-project-file
  "Find a file in a project using the parameters from a `file_content` request.
  Returns the path as as string. If the module name is the same as the project
  name then files are served from the project directly."
  [bolt-projects-dir project-ref mount module path]
  (if (is-bolt-project? (str bolt-projects-dir "/" project-ref))
    (let [project-root (get-project-root bolt-projects-dir project-ref)
          project-config (read-bolt-project-config project-root)
          project-name (get project-config :name)
          modulepath (get-project-modulepath project-config)
          module-root (if (= project-name module)
                        project-root
                        (find-project-module project-root module modulepath))
          file-path (str module-root "/" (mount->path-component mount) "/" path)]
      (if (fs/exists? file-path)
        file-path))))

(def project-routes
  "Bidi routing table for project file_content endpoint. This is done separately
  because we have to do additional routing after branching on the query
  parameter, which is not natively supported in bidi."

  ["" {[[#"tasks|modules" :mount-point] "/" :module "/" [#".+" :file-path]] :basic
       [[#"plugins|pluginfacts" :mount-point] [#".*" :file-path]] :pluginsync}])

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
  [bolt-projects-dir request]
  (let [project (get-in request [:params "project"])
        path (get-in request [:params :rest])
        match (bidi/match-route project-routes path)]
    (if match
      (let [mount-point (get-in match [:route-params :mount-point])
            mount-type (get-in match [:handler])
            module (get-in match [:route-params :module])
            file-path (get-in match [:route-params :file-path])]
        (case mount-type
          :basic (make-file-content-response
                  (find-project-file bolt-projects-dir project mount-point module file-path)
                  file-path)
          ;; :pluginsync nil
          {:status 400
           :headers {"Content-Type" "text/plain"}
           :body (i18n/tru "Unsupported mount: {0}" mount-point)})))))

(defn file-content-handler
  "Handle file_content requests and dispatch them to the correct handler for
  environments or projects."
  [bolt-projects-dir ruby-request-handler request]
  (let [project (get-in request [:params "project"])
        environment (get-in request [:params "environment"])]
    (cond
      (and project environment)
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (i18n/tru "A file_content request cannot specify both `environment` and `project` query parameters.")}
      (and (nil? project) (nil? environment))
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (i18n/tru "A file_content request must include an `environment` or `project` query parameter.")}
      project
      (handle-project-file-content bolt-projects-dir request)

      :else
      (ruby-request-handler request))))
