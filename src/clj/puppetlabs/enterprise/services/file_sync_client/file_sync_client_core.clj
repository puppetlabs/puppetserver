(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.http.client.common :as http-client])
  (:import (org.eclipse.jgit.transport HttpTransport)
           (clojure.lang IFn Agent)
           (java.io IOException)
           (org.eclipse.jgit.api.errors GitAPIException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

(def AgentError
  {:type                        (schema/eq ::error)
   :message                     String
   (schema/optional-key :cause) Exception})

(defn agent-error? [x] (not (schema/check AgentError x)))

(def AgentState
  "A schema which describes a valid state of the agent."
  (schema/if #(= (:status %) :failed)
    {:status (schema/eq :failed)
     :error  AgentError}
    {:status (schema/enum :successful :created)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Private

(defn create-http-client
  [ssl-context]
  (sync/create-client
    (if ssl-context {:ssl-context ssl-context} {})))

(schema/defn ^:always-validate get-body-from-latest-commits-payload
  :- common/LatestCommitsPayload
  [response]
  (when-let [failure (schema/check common/LatestCommitsResponse response)]
    (throw+ {:type    ::error
             :message (str "Response for latest commits unexpected, detail: " failure)}))
  (cheshire/parse-string (:body response)))

(schema/defn ^:always-validate get-latest-commits-from-server
  :- common/LatestCommitsPayload
  "Request information about the latest commits available from the server.
  The latest commits are requested from the URL in the supplied
  `server-api-url` argument.  Returns the payload from the response.
  Throws an 'AgentError' if an error occurs."
  [client :- http-client/HTTPClient
   server-api-url :- schema/Str]
  (let [latest-commits-url (str
                             server-api-url
                             common/latest-commits-sub-path)]
    (try
      (get-body-from-latest-commits-payload
        (http-client/get client latest-commits-url {:as :text}))
      (catch IOException e
        (throw+ {:type ::error
                 :message (str "Unable to get latest-commits from server ("
                               latest-commits-url ").")
                 :cause e})))))

(defn message-with-repo-info
  "Add repository information to a message.  Name is the name of the repository.
  Directory is the location in which the client repository is intended to
  reside."
  [message name directory]
  (str message ".  Name: " name ".  Directory: " directory "."))

(defn do-fetch
  "Fetch the latest content for a repository from the server.  'name' is
  the name of the repository.  'latest-commit-id' is the ID of the latest
  commit on the server for the repository.  'target-dir' is the location
  in which the repository is intended to reside."
  [name latest-commit-id target-dir]
  (if-let [repo (jgit-client/get-repository-from-git-dir target-dir)]
    (when-not (= (jgit-client/head-rev-id repo) latest-commit-id)
      (log/infof "File sync updating '%s' to %s" name latest-commit-id)
      (jgit-client/fetch repo)
      (log/info
        (str "File sync fetch of '" name "' successful.  New latest commit: "
             (jgit-client/head-rev-id repo))))
    (throw (IllegalStateException.
             (message-with-repo-info
               (str "File sync found a directory that already exists but does "
                    "not have a repository in it")
               name
               target-dir)))))

(defn do-clone
  "Clone the latest content for a repository from the server.  'name' is
  the name of the repository.  'server-repo-url' is the URL under which
  the repository resides on the server.  'target-dir' is the directory in which
  the client repository should be stored."
  [name server-repo-url target-dir]
  (log/infof "File sync cloning '%s' to: %s" name target-dir)
  (let [git (jgit-client/clone server-repo-url target-dir true)]
    (log/info
      (str "File sync clone of '" name "' successful.  New latest commit: "
           (jgit-client/head-rev-id (.getRepository git))))))

(defn apply-updates-to-repo
  "Apply updates from the server to the client repository.  'name' is the
  name of the repository.  'server-repo-url' is the URL under which the
  repository resides on the server.  'latest-commit-id' is the id of the
  latest commit on the server for the repository.  'target-dir' is the
  location in which the client repository is intended to reside."
  [name server-repo-url latest-commit-id target-dir]
  (let [fetch? (fs/exists? target-dir)]
    (try
      (if fetch?
        (do-fetch name latest-commit-id target-dir)
        (do-clone name server-repo-url target-dir))
      (catch GitAPIException e
        (throw+ {:type    ::error
                 :message (message-with-repo-info
                            (str "File sync was unable to "
                                 (if fetch? "fetch" "clone")
                                 " from server repo")
                            name
                            target-dir)
                 :cause e})))))

(defn process-repo-for-updates
  "Process a repository for any possible updates which may need to be applied.
  'server-repo-url' is the base URL at which the repository is hosted on the
  server.  'name' is the name of the repository.  'target-dir' is the location in
  which the client repository is intended to reside.  'latest-commit-id' is
  the commit id of the latest commit in the server repo."
  [server-repo-url name target-dir latest-commit-id]
  (let [server-repo-url  (str server-repo-url "/" name)
        target-dir       (fs/file target-dir)]
    (apply-updates-to-repo name server-repo-url latest-commit-id target-dir)))

(defn process-repos-for-updates
  "Process through all of the repos configured with the
  service for any updates which may be available on the server.
  'server-repo-url' is the base URL at which the repository is hosted on the
  server.  'repos' is the repos section of the file sync client configuration."
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

; This function is marked 'always-validate' to ensure that the agent is always
; left in a valid state.
(schema/defn ^:always-validate sync-on-agent :- AgentState
  "Runs the sync process on the agent."
  [agent-state config http-client]
  (log/debug "file sync process running ...")
  (try+
    (let [filesync-server-url (:server-url config)
          server-repo-path (:server-repo-path config)
          server-api-path (:server-api-path config)
          repos (:repos config)]
      (log/debugf "File sync client repos: %s" repos)
      (process-repos-for-updates
        http-client
        (str filesync-server-url server-repo-path)
        (str filesync-server-url server-api-path)
        repos)
      {:status :successful})
    (catch agent-error? error
      (let [message (str "File sync failure: " (:message error))]
        (if-let [cause (:cause error)]
          (log/error cause message)
          (log/error message)))
      {:status :failed
       :error  error})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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

(defn create-agent
  "Creates and returns the agent used by the file sync client service.
  'request-shutdown' is a function that will be called in the event of a fatal
  error during the sync process when the entire applications needs to be shut down."
  [request-shutdown]
  (agent
    {:status :created}
    :error-mode :fail
    :error-handler (fn [_ error]
                     ; disaster!  shut down the entire application.
                     (log/error error "Fatal error during file sync, requesting shutdown.")
                     (request-shutdown))))

(schema/defn ^:always-validate start-periodic-sync-process!
  "Synchronizes the repositories sypecified in 'config' by sending the agent an
  action.  It is important that this function only be called once, during service
  startup.  (Although, note that one-off sync runs can be triggered by sending
  the agent a 'sync-on-agent' action.)

  'schedule-fn' is the function that will be invoked after each iteration of the
  sync process to schedule the next iteration."
  [sync-agent :- Agent
   schedule-fn :- IFn
   config :- FileSyncClientServiceRawConfig
   http-client :- http-client/HTTPClient]
  (let [periodic-sync (fn [& args]
                        (-> (apply sync-on-agent args)
                            (assoc ::schedule-next-run? true)))
        send-to-agent #(send-off sync-agent periodic-sync config http-client)]
    (add-watch sync-agent
               ::schedule-watch
               (fn [key* ref* old-state new-state]
                 (when (::schedule-next-run? new-state)
                   (log/debug "Scheduling the next iteration of the sync process.")
                   (schedule-fn send-to-agent))))
    ; The watch is in place, now send the initial action.
    (send-to-agent)))
