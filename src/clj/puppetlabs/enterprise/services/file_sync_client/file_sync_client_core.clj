(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.http.client.common :as http-client])
  (:import (org.eclipse.jgit.transport HttpTransport)
           (clojure.lang IFn Agent)
           (java.io IOException)
           (org.eclipse.jgit.api.errors GitAPIException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Schemas

(def ReposConfig
  "A schema describing the configuration data for the repositories managed by
  this service.  Each repository is a key-value pair; the key is the repo ID,
  the value is the filesystem path to the repository."
  {schema/Keyword schema/Str})

(def Config
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
   :repos                             ReposConfig
   (schema/optional-key :ssl-cert)    schema/Str
   (schema/optional-key :ssl-key)     schema/Str
   (schema/optional-key :ssl-ca-cert) schema/Str})

(def SyncError
  {:type                        (schema/eq ::error)
   :message                     String
   (schema/optional-key :cause) Exception})

(defn sync-error? [x] (not (schema/check SyncError x)))

(def SingleRepoState
  "A schema which describes a valid state of a repo after a sync"
  (schema/if #(= (:status %) :failed)
    {:status (schema/eq :failed)
     :cause SyncError}
    ;; It's possible for the latest commit to be nil if no
    ;; commits have been made against the server-side repo, as the
    ;; server's bare repo will still be cloned on the client-side.
    {:status (schema/enum :synced :unchanged)
     :latest-commit (schema/maybe schema/Str)}))

(def RepoStates
  "A schema which describes a map containing valid states for
  numerous repos after a sync"
  {schema/Str SingleRepoState})

(def AgentState
  "A schema which describes a valid state of the agent."
  (schema/if #(= (:status %) :failed)
    {:status (schema/eq :failed)
     :error  SyncError}
    {:status (schema/enum :successful :created :partial-success)
     :repos  RepoStates}))

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
  Throws an 'SyncError' if an error occurs."
  [client :- (schema/protocol http-client/HTTPClient)
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

(defn fetch-if-necessary!
  "Open target-dir as a Git repo; if latest-commit-id is different than the
   latest commit ID in that repo, run 'git fetch'.  name is only used for
   logging and error messages."
  [name target-dir latest-commit-id]
  (if-let [repo (jgit-utils/get-repository-from-git-dir target-dir)]
    (when-not (= latest-commit-id (jgit-utils/head-rev-id repo))
      (log/infof "Fetching '%s' to %s" name latest-commit-id)
      (jgit-utils/fetch repo))
    (throw (IllegalStateException.
             (message-with-repo-info
               (str "File sync found a directory that already exists but does "
                    "not have a repository in it")
               name
               target-dir)))))

(defn apply-updates-to-repo
  "Apply updates from the server to the client repository.  'name' is the
  name of the repository.  'server-repo-url' is the URL under which the
  repository resides on the server.  'latest-commit-id' is the id of the
  latest commit on the server for the repository.  'target-dir' is the
  location in which the client repository is intended to reside. Returns
  the status information for the repo."
  [name server-repo-url latest-commit-id target-dir]
  (let [fetch? (fs/exists? target-dir)
        clone? (not fetch?)
        initial-commit-id (jgit-utils/head-rev-id-from-git-dir target-dir)]
    (try
      (if clone?
        (jgit-utils/clone server-repo-url target-dir true)
        (fetch-if-necessary! name target-dir latest-commit-id))
      (let [current-commit-id (jgit-utils/head-rev-id-from-git-dir target-dir)
            changed? (or clone? (not= current-commit-id initial-commit-id))]
        (log/info (str (if fetch? "fetch" "clone") "of '" name
                       "' successful.  New latest commit: " current-commit-id))
        {:status        (if changed? :synced :unchanged)
         :latest-commit current-commit-id})
      (catch GitAPIException e
        (throw+ {:type    ::error
                 :message (message-with-repo-info
                            (str "File sync was unable to "
                                 (if fetch? "fetch" "clone")
                                 " from server repo")
                            name
                            target-dir)
                 :cause   e})))))

(defn process-repo-for-updates
  "Process a repository for any possible updates which may need to be applied.
  'server-repo-url' is the base URL at which the repository is hosted on the
  server.  'name' is the name of the repository.  'target-dir' is the location in
  which the client repository is intended to reside.  'latest-commit-id' is
  the commit id of the latest commit in the server repo. Returns status information
  about the sync process for the repo."
  [server-repo-url name target-dir latest-commit-id callback-fn]
  (let [server-repo-url (str server-repo-url "/" name)
        target-dir (fs/file target-dir)
        status (apply-updates-to-repo name
                                      server-repo-url
                                      latest-commit-id
                                      target-dir)]
    (when (and callback-fn (= :synced (:status status)))
      (callback-fn (keyword name) status))
    status))

(schema/defn process-repos-for-updates :- RepoStates
  "Process the repositories for any updates which may be available on the server."
  [repos :- ReposConfig
   repo-base-url :- String
   latest-commits :- common/LatestCommitsPayload
   callbacks]
  (log/debugf "File sync latest commits from server: %s" latest-commits)
  (into {}
        (for [[repo-name target-dir] repos]
          (let [name (name repo-name)]
            (if (contains? latest-commits name)
              (let [latest-commit (latest-commits name)]
                (try+
                  {name (process-repo-for-updates repo-base-url
                                                  name
                                                  target-dir
                                                  latest-commit
                                                  (get callbacks repo-name))}
                  (catch sync-error? e
                    (log/errorf
                      (str "Error syncing repo: " (:message e))
                      name)
                    {name {:status :failed
                           :cause  e}})))
              (log/errorf
                "File sync did not find matching server repo for client repo: %s"
                name))))))

; This function is marked 'always-validate' to ensure that the agent is always
; left in a valid state.
(schema/defn ^:always-validate sync-on-agent :- AgentState
  "Runs the sync process on the agent."
  [agent-state
   {:keys [server-url server-repo-path server-api-path repos]} :- Config
   http-client
   callbacks]
  (log/debug "File sync process running on repos " repos)
  (try+
    (let [latest-commits (get-latest-commits-from-server http-client
                                                         (str server-url server-api-path))
          repo-states (process-repos-for-updates repos
                                                 (str server-url server-repo-path)
                                                 latest-commits
                                                 callbacks)
          full-success? (every? #(not= (:status %) :failed)
                                (vals repo-states))]
      {:status (if full-success? :successful :partial-success)
       :repos repo-states})
    (catch sync-error? error
      (let [message (str "File sync failure: " (:message error))]
        (if-let [cause (:cause error)]
          (log/error cause message)
          (log/error message)))
      {:status :failed
       :error  error})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn register-callback!
  "Given the client service's context, registers a callback function.
   Throws an exception if the callback is not a function."
  [context repo-id callback-fn]
  (when-not (instance? IFn callback-fn)
    (throw
      (IllegalArgumentException.
        "Error: callback must be a function")))
  (swap! (:callbacks context) assoc repo-id callback-fn))

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
      (jgit-utils/create-connection-factory)
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
  "Synchronizes the repositories specified in 'config' by sending the agent an
  action.  It is important that this function only be called once, during service
  startup.  (Although, note that one-off sync runs can be triggered by sending
  the agent a 'sync-on-agent' action.)

  'schedule-fn' is the function that will be invoked after each iteration of the
  sync process to schedule the next iteration."
  [sync-agent :- Agent
   schedule-fn :- IFn
   config :- Config
   http-client :- (schema/protocol http-client/HTTPClient)
   callbacks]
  (let [periodic-sync (fn [& args]
                        (-> (apply sync-on-agent args)
                            (assoc ::schedule-next-run? true)))
        send-to-agent #(send-off sync-agent periodic-sync config http-client callbacks)]
    (add-watch sync-agent
               ::schedule-watch
               (fn [key* ref* old-state new-state]
                 (when (::schedule-next-run? new-state)
                   (log/debug "Scheduling the next iteration of the sync process.")
                   (schedule-fn send-to-agent))))
    ; The watch is in place, now send the initial action.
    (send-to-agent)))
