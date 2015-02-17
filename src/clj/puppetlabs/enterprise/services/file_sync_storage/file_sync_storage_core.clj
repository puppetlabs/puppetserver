(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
  (:import (java.io File)
           (org.eclipse.jgit.api InitCommand)
           (org.eclipse.jgit.lib RepositoryBuilder Repository))
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.jgit-client :as client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.ringutils :as ringutils]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+]]
            [compojure.core :as compojure]
            [liberator.core :as liberator]
            [puppetlabs.enterprise.file-sync-common :as common]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def GitRepo
  "Schema defining a Git repository.

  The keys should have the following values:

    * :sub-path  - The path under the Git server's base path at which the
                   repository's content should reside."
  {:sub-path                                  schema/Str
   (schema/optional-key :http-push-enabled)   Boolean})

(def FileSyncServiceRawConfig
  "Schema defining the full content of the JGit service configuration.

  The keys should have the following values:

    * :base-path - The base path on the Git server under which all of the
                   repositories it is managing should reside.

    * :repos     - A vector with metadata about each of the individual
                   Git repositories that the server manages."
  {:base-path                               String
   :repos                                   [GitRepo]
   (schema/optional-key :ssl-cert)          schema/Str
   (schema/optional-key :ssl-key)           schema/Str
   (schema/optional-key :ssl-ca-cert)       schema/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn initialize-server-base-path!
  "Initialize the base path on the server under which all other git
  repositories will be hosted.  Expects, as an argument, a File representing
  the server base path.  Returns nil if initialization was successful or
  throws an Exception on failure."
  [server-base-path]
  {:pre  [(instance? File server-base-path)]
   :post [(nil? %)]}
  (try+
    (ks/mkdirs! server-base-path)
    (catch map? m
      (throw
        (Exception.
          (str "Problem occurred creating jgit server base-path: "
               (:message m)))))
    (catch Exception e
      (throw (Exception.
               (str "Problem occurred creating jgit server base-path: "
                    (.getMessage e)) e))))
  nil)

(defn initialize-repo!
  "Initialize a directory (specified by the `repo-path` parameter) for git
  repository content to be hosted on the server.   `repo-path` should be
  something that is coercible to a File.  If the `allow-anonymous-push?` flag
  is set, the 'http.receive-pack' setting in the git directory configuration to
  '1' so that the directory is exported for anonymous access.  Otherwise,
  http.receivepack will be set to 0, which disables all pushes to the repository
  over HTTP.  Returns nil."
  [repo-path allow-anonymous-push?]
  {:post [(nil? %)]}
  (doto
      ; On the server-side, the git repo has to be initialized
      ; as bare in order to be served up to remote clients
      (-> (InitCommand.)
          (.setDirectory (fs/file repo-path))
          (.setBare true)
          (.call)
          (.getRepository)
          (.getConfig))
    (.setInt "http" nil "receivepack" (if allow-anonymous-push? 1 0))
    (.save))
  nil)

(schema/defn get-bare-repo :- Repository
  "Given a git directory (as something coercible to a File), return an instance
  of `org.eclipse.jgit.lib.Repository` for that repository."
  [git-dir]
  (-> (RepositoryBuilder.)
      (.setGitDir (io/as-file git-dir))
      (.build)))

(defn latest-commit-on-master
  "Returns the SHA-1 revision ID of the latest commit on the master branch of
   the repository specified by the given `git-dir`.  Returns `nil` if commits
   have been made on the repository."
  [git-dir]
  {:pre [(instance? File git-dir)]
   :post [(or (string? %) (nil? %))]}
  (when-let [ref (-> git-dir
                     (get-bare-repo)
                     (.getRef "refs/heads/master"))]
    (-> ref
        (.getObjectId)
        (client/commit-id))))

(defn compute-latest-commits
  "Computes the latest commit for each repository in `sub-paths`."
  [base-path sub-paths]
  (reduce
    (fn [acc sub-path]
      (let [repo-path (fs/file base-path sub-path)
            rev (latest-commit-on-master repo-path)]
        (assoc acc sub-path rev)))
    {}
    sub-paths))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app
(defn build-routes
  "Builds the compojure routes from the given configuration values."
  [base-path sub-paths]
  (compojure/routes
    (compojure/ANY common/latest-commits-sub-path []
      (liberator/resource
        :available-media-types  ["application/json"]
        :handle-ok              (fn [context]
                                  (compute-latest-commits
                                    base-path sub-paths))))))

(defn build-handler
  "Builds a ring handler from the given configuration values."
  [base-path sub-paths]
  (-> (build-routes base-path sub-paths)
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate initialize-repos! :- nil
  "Initialize a vector of git repository directories on the server.  The
  repositories to initialize as well as the base directory under which the
  repositories should reside should be specified in the supplied config."
  [config :- FileSyncServiceRawConfig]
  (let [base-path (:base-path config)]
    (log/infof "Initializing file sync server base path: %s" base-path)
    (initialize-server-base-path! (fs/file base-path))
    (doseq [{:keys [sub-path http-push-enabled]}  (:repos config)]
      (let [repo-path (fs/file base-path sub-path)]
        (log/infof "Initializing file sync repository path: %s" repo-path)
        (initialize-repo! repo-path http-push-enabled))))
  nil)
