(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.http.client.common :as http-client]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Schemas
;
(def RepoConfig
  "Schema defining a client repository.

  The keys should have the following values:

    * :name       - The name of the repository.  Used by the client and server
                    to uniquely identify the repository.

    * :target-dir - Directory to which the repository content should be
                    deployed."
 {:name       schema/Str
  :target-dir schema/Str})

(def FileSyncClientServiceRawConfig
  "Schema defining the full content of the file sync client service
  configuration.

  The keys should have the following values:

    * :poll-interval - Number of seconds which the file sync client service
                       should wait between attempts to poll the server for
                       latest available content.

    * :server-url    - Base URL of the repository server.

    * :repos         - A vector with metadata about each of the repositories
                       that the server manages."
  {:poll-interval                           schema/Int
   :server-url                              schema/Str
   :server-repo-path                        schema/Str
   :server-api-path                         schema/Str
   :repos                                   [RepoConfig]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Private

(schema/defn ^:always-validate get-body-from-latest-commits-payload
  :- common/LatestCommitsPayload
  [response]
  (let [content-type (get-in response [:headers "content-type"])]
    (if (.startsWith content-type "application/json")
      (if-let [body (:body response)]
        (cheshire/parse-string body)
        (throw (Exception.
                 (str "Did not get response body for latest-commits.  "
                      "Response: " response))))
      (throw (Exception.
               (str "Did not get json response for latest-commits.  "
                    "Response: " response))))))

(schema/defn ^:always-validate get-latest-commits-from-server
  :- common/LatestCommitsPayload
  "Request information about the latest commits available from the server.
  The latest commits are requested from the URL in the supplied
  `server-api-url` argument.  Returns a latest-commit payload which
  should validate against the FileSyncLatestCommits schema if successful or
  throws an Exception on failure."
  [server-api-url :- schema/Str
   client :- http-client/HTTPClient]
  (let [latest-commits-url (str
                             server-api-url
                             common/latest-commits-sub-path)]
    (try
      (get-body-from-latest-commits-payload
        (http-client/get client latest-commits-url {:as :text}))
      (catch Exception e
        (throw (Exception. (str "Unable to get latest-commits from server ("
                                latest-commits-url
                                ").")
                           e))))))

(defn message-with-repo-info
  "Add repository information to a message.  Name is the name of the repository.
  Directory is the location in which the client repository is intended to
  reside."
  [message name directory]
  (str message ".  Name: " name ".  Directory: " directory "."))

(defn do-pull
  "Pull the latest content for a repository from the server.  Name is the name
  of the repository.  latest-commit-id is the id of the latest commit on the
  server for the repository.  target-dir is the location in which the client
  repository is intended to reside.  Throws an `Exception` on failure."
  [name latest-commit-id target-dir]
  (if-let [repo (jgit-client/get-repository target-dir)]
    (when-not (= (jgit-client/head-rev-id repo) latest-commit-id)
      (log/infof "File sync updating '%s'"
                 name
                 latest-commit-id)
      (try
        (let [pull-result (jgit-client/pull repo)]
          (if (.isSuccessful pull-result)
            (log/info
              (str "File sync update of '"
                   name
                   "' successful.  New head commit: "
                   (-> pull-result
                       (.getMergeResult)
                       (.getNewHead)
                       (jgit-client/commit-id))))
            (throw (Exception.
                     (message-with-repo-info
                       (str "File sync repo pull result was not successful.  "
                            "Result: "
                            (.toString pull-result))
                       name
                       target-dir)))))
        (catch Exception e
          (throw (Exception.
                   (message-with-repo-info
                     "File sync was unable to pull a server repo"
                     name
                     target-dir)
                   e)))))
    (throw (Exception.
             (message-with-repo-info
               (str "File sync found a directory that already exists but does "
                    "not have a repository in it")
               name
               target-dir)))))

(defn do-clone
  "Clone the latest content for a repository from the server.  Name is
  the name of the repository.  server-repo-url is the URL under which
  the repository resides on the server.  target-dir is the directory in which
  the client repository should be stored.  Throws an `Exception` on failure."
  [name server-repo-url target-dir]
  (try
    (log/infof "File sync cloning '%s' to: %s" name target-dir)
    (let [git (jgit-client/clone server-repo-url target-dir)]
      (log/info (str "File sync clone of '"
                     name
                     "' successful.  New head commit: "
                     (jgit-client/head-rev-id (.getRepository git)))))
    (catch Exception e
      (throw (Exception.
               (message-with-repo-info
                 "File sync was unable to clone a server repo"
                 name
                 target-dir)
               e)))))

(defn apply-updates-to-repo
  "Apply updates from the server to the client repository.  Name is the
  name of the repository.  server-repo-url is the URL under which the
  repository resides on the server.  latest-commit-id is the id of the
  latest commit on the server for the repository.  target-dir is the
  location in which the client repository is intended to reside.  Returns
  true on success, else false on failure."
  [name server-repo-url latest-commit-id target-dir]
  (if (fs/exists? target-dir)
    (do-pull name latest-commit-id target-dir)
    (do-clone name server-repo-url target-dir)))

(defn process-repo-for-updates
  "Process a repository for any possible updates which may need to be applied.
  server-repo-url is the base URL at which the repository is hosted on the
  server.  Name is the name of the repository.  target-dir is the location in
  which the client repository is intended to reside.  latest-commit-id is
  the commit id of the latest commit in the server repo."
  [server-repo-url name target-dir latest-commit-id]
  (let [server-repo-url  (str server-repo-url "/" name)
        target-dir       (fs/file target-dir)]
    (apply-updates-to-repo name server-repo-url latest-commit-id target-dir)))

(defn process-repos-for-updates
  "Process through all of the repos configured with the
  service for any updates which may be available on the server.
  server-repo-url is the base URL at which the repository is hosted on the
  server.  Repos is the repos section of the file sync client configuration."
  [server-repo-base-url server-api-url repos client]
  (let [latest-commits (get-latest-commits-from-server server-api-url client)]
    (log/debugf "File sync latest commits from server: %s" latest-commits)
    (doseq [repo repos]
      (let [name (:name repo)]
        (if (contains? latest-commits name)
          (process-repo-for-updates server-repo-base-url
                                    name
                                    (:target-dir repo)
                                    (latest-commits name))
          (log/errorf
            "File sync did not find matching server repo for client repo: %s"
            name))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate start-worker :- nil
  "Start a worker loop for the file sync client service.  This loop is
  intended to stay alive until told to stop at service shutdown.  At a
  poll-interval configured in the supplied config, the loop processes
  configured repositories for any needed content updates.  The supplied
  config derives from the file sync client service configuration.  The
  supplied shutdown-requested? promise is retained so that when a
  corresponding stop-worker call is made later with the same promise, the
  worker can be appropriately stopped."
  [config :- FileSyncClientServiceRawConfig
   shutdown-requested?]
  (log/info "File sync client worker started")
  (let [filesync-server-url (:server-url config)
        server-repo-path    (:server-repo-path config)
        server-api-path     (:server-api-path config)
        poll-interval       (* (:poll-interval config) 1000)
        repos               (:repos config)
        client              (sync/create-client {})]
    (log/debugf "File sync client repos: %s" repos)
    (while (not (realized? shutdown-requested?))
      (try
        (process-repos-for-updates
          (str filesync-server-url server-repo-path)
          (str filesync-server-url server-api-path)
          repos
          client)
        (catch Exception e
          (log/error (str "File sync failure.  Cause: "
                      e
                      (if-let [sub-cause (.getCause e)]
                        (str "  Cause: " sub-cause)
                        "")))
          (log/debug e "File sync failure.")))
      (Thread/sleep poll-interval))
    (http-client/close client))
  (log/info "File sync client worker stopped")
  nil)

(defn stop-worker
  "Stop the file sync client service worker.  The shutdown-requested?
  argument promise supplied to this function should be the same as the one
  passed into the start-worker function call used to start the worker."
  [shutdown-requested?]
  (deliver shutdown-requested? true))