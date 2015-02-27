(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.http.client.common :as http-client])
  (:import (org.eclipse.jgit.transport HttpTransport)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Schemas

(def FileSyncClientServiceRawConfig
  "Schema defining the full content of the file sync client service
  configuration.

  The keys should have the following values:

    * :poll-interval - Number of seconds which the file sync client service
                       should wait between attempts to poll the server for
                       latest available content.

    * :server-url    - Base URL of the repository server.

    * :repos         - A map with metadata about each of the repositories
                       that the server manages. Each key should be the name
                       of a repository, and each value should be the directory
                       to which the repository content should be deployed as
                       a string."
  {:poll-interval                     schema/Int
   :server-url                        schema/Str
   :server-repo-path                  schema/Str
   :server-api-path                   schema/Str
   :repos                             {schema/Keyword schema/Str}
   (schema/optional-key :ssl-cert)    schema/Str
   (schema/optional-key :ssl-key)     schema/Str
   (schema/optional-key :ssl-ca-cert) schema/Str})
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Private

(defn create-http-client
  [ssl-context]
  (sync/create-client
    (if ssl-context {:ssl-context ssl-context} {})))

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
  [client :- http-client/HTTPClient
   server-api-url :- schema/Str]
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

(defn do-fetch
  "Fetch the latest content for a repository from the server. Name is
  the name of the repository.  latest-commit-id is the id of the latest
  commit on the server for the repository.  target-dir is the location
  in which the repository is intended to reside.  Throws an `Exception`
  on failure."
  [name latest-commit-id target-dir]
  (if-let [repo (jgit-client/get-repository-from-git-dir target-dir)]
    (when-not (= (jgit-client/head-rev-id repo) latest-commit-id)
      (log/infof "File sync updating '%s'"
                 name
                 latest-commit-id)
      (try
        (jgit-client/fetch repo)
        (log/info
          (str "File sync fetch of '" name
               "' successful.  New head commit: "
               (jgit-client/head-rev-id repo)))

        (catch Exception e
          (throw (Exception. (message-with-repo-info
                               "File sync was unable to fetch update from server repo"
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
    (let [git (jgit-client/clone server-repo-url target-dir true)]
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
    (do-fetch name latest-commit-id target-dir)
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

(schema/defn process-repos-for-updates
  "Process through all of the repos configured with the
  service for any updates which may be available on the server.
  server-repo-url is the base URL at which the repository is hosted on the
  server.  Repos is the repos section of the file sync client configuration."
  [client server-repo-base-url server-api-url repos]
  (let [latest-commits (get-latest-commits-from-server client server-api-url)]
    (log/debugf "File sync latest commits from server: %s" latest-commits)
    (doseq [[repo-name target-dir] repos]
      (let [name (name repo-name)]
        (if (contains? latest-commits name)
          (process-repo-for-updates server-repo-base-url
                                    name
                                    target-dir
                                    (latest-commits name))
          (log/errorf
            "File sync did not find matching server repo for client repo: %s"
            name))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate configure-jgit-client-ssl!
  "Ensures that the JGit client is configured for SSL, if necessary.  The JGit
  client's connection is configured through global state, via the static
  'connectionFactory' defined on org.eclipse.jgit.transport.HttpTransport.
  This functions creates a connection factory based on the given SSL context
  and mutates that global state.  This is unforunate; we would like to configure
  this in a way that does not involve mutating global state, but JGit does not
  currently allow this - see https://bugs.eclipse.org/bugs/show_bug.cgi?id=460483"
  [ssl-context :- common/SSLContextOrNil]
  (-> ssl-context
      (jgit-client/create-connection-factory)
      (HttpTransport/setConnectionFactory)))

(schema/defn ^:always-validate perform-sync!
  "Synchronizes the repositories specified in 'config'."
  [config :- FileSyncClientServiceRawConfig
   http-client :- http-client/HTTPClient]
  (let [filesync-server-url (:server-url config)
        server-repo-path    (:server-repo-path config)
        server-api-path     (:server-api-path config)
        repos               (:repos config)]
    (log/debugf "File sync client repos: %s" repos)
    (try
      (process-repos-for-updates
        http-client
        (str filesync-server-url server-repo-path)
        (str filesync-server-url server-api-path)
        repos)
      (catch Exception e
        (log/error (str "File sync failure.  Cause: "
                        e
                        (if-let [sub-cause (.getCause e)]
                          (str "  Cause: " sub-cause)
                          "")))
        (log/debug e "File sync failure.")))))
