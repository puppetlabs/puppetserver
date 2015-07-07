(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+ throw+]]
            [cheshire.core :as json]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.http.client.common :as http-client])
  (:import (org.eclipse.jgit.transport HttpTransport)
           (clojure.lang IFn Agent Atom)
           (java.io IOException)
           (org.eclipse.jgit.api.errors GitAPIException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Schemas

(def ClientContext
  "A schema describing the service context for the File Sync Client service"
  {:callbacks Atom})

(def ReposConfig
  "A schema describing the configuration data for the repositories managed by
  this service. This is just a list of repository names."
  [schema/Keyword])

(def Config
  "Schema defining the full content of the file sync client service
  configuration.

  The keys should have the following values:

    * :poll-interval - Number of seconds which the file sync client service
                       should wait between attempts to poll the server for
                       latest available content.

    * :repos         - A vector containing the ids of all repositories managed
                       by the client service, each of which should correspond to
                       the id of a repo in the storage service."
  {:poll-interval                     schema/Int
   :server-repo-path                  schema/Str
   :server-api-path                   schema/Str
   :repos                             ReposConfig
   (schema/optional-key :ssl-cert)    schema/Str
   (schema/optional-key :ssl-key)     schema/Str
   (schema/optional-key :ssl-ca-cert) schema/Str})

(def CoreConfig
  "Schema defining the configuration passed to the sync agent"
  {:client-config Config
   :server-url schema/Str
   :data-dir schema/Str})

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
     :latest-commit (schema/maybe schema/Str)
     (schema/optional-key :submodules) {schema/Str (schema/recursive #'SingleRepoState)}}))

(def RepoStates
  "A schema which describes a map containing valid states for
  numerous repos after a sync"
  {schema/Keyword SingleRepoState})

(def AgentState
  "A schema which describes a valid state of the agent."
  (schema/conditional
    #(= (:status %) :failed)
    {:status (schema/eq :failed)
     :error SyncError
     (schema/optional-key :schedule-next-run?) Boolean}

    #(= (:status %) :created)
    {:status (schema/eq :created)}

    :else
    {:status (schema/enum :successful :partial-success)
     :repos RepoStates
     (schema/optional-key :schedule-next-run?) Boolean}))

(def Callbacks
  "A schema which describes the storage of repo ids with their associated
  callbacks"
  {schema/Keyword #{IFn}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Private

(defn path-to-data-dir
  [data-dir]
  (str data-dir "/client"))

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
  (common/parse-latest-commits-response response))

(schema/defn agent-state->latest-commits-payload
  "Given an instance of AgentState, return the information that should be
   POST'ed to /latest-commits."
  [{:keys [repos]} :- AgentState]
  (when repos
    {:repos (ks/mapvals
              ; Exceptions aren't serializable as JSON.
              #(ks/dissoc-in % [:cause :cause])
              repos)}))

(schema/defn ^:always-validate get-latest-commits-from-server
  :- common/LatestCommitsPayload
  "Request information about the latest commits available from the server.
  The latest commits are requested from the URL in the supplied
  `server-api-url` argument.  Returns the payload from the response.
  Throws an 'SyncError' if an error occurs."
  [client :- (schema/protocol http-client/HTTPClient)
   server-api-url :- schema/Str
   agent-state :- AgentState]
  (let [latest-commits-url (str server-api-url common/latest-commits-sub-path)]
    (try
      (let [body (agent-state->latest-commits-payload agent-state)
            response (http-client/post
                       client latest-commits-url
                       {:as :text :body (when body
                                          (json/generate-string body))})]
        (get-body-from-latest-commits-payload response))
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
               (str "File sync found a non-empty directory which does "
                    "not have a repository in it")
               name
               target-dir)))))

(defn non-empty-dir?
  "Returns true if path exists, is a directory, and contains at least 1 file."
  [path]
  (-> path
    io/as-file
    .listFiles
    count
    (> 0)))

(defn apply-updates-to-repo
  "Apply updates from the server to the client repository.  'name' is the
  name of the repository.  'server-repo-url' is the URL under which the
  repository resides on the server.  'latest-commit-id' is the id of the
  latest commit on the server for the repository.  'target-dir' is the
  location in which the client repository is intended to reside. Returns
  the status information for the repo."
  [name server-repo-url latest-commit-id target-dir]
  (let [fetch? (non-empty-dir? target-dir)
        clone? (not fetch?)
        initial-commit-id (jgit-utils/head-rev-id-from-git-dir target-dir)]
    (try
      (if clone?
        (jgit-utils/clone server-repo-url target-dir true)
        (fetch-if-necessary! name target-dir latest-commit-id))
      (let [current-commit-id (jgit-utils/head-rev-id-from-git-dir target-dir)
            synced? (or clone? (not= current-commit-id initial-commit-id))]
        (when synced?
          (log/info (str (if fetch? "fetch" "clone") " of '" name
                      "' successful.  New latest commit: " current-commit-id)))
        {:status (if synced? :synced :unchanged)
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

(defn extract-submodule-name
  [submodule]
  (re-find #"[^\/]+$" submodule))

(defn process-submodules-for-updates
  "Process a repo's submodules for any possible updates which may need to be
  applied."
  [server-repo-url submodules-commit-info submodule-root parent-repo]
  (into
    {}
    (for [[submodule commit] submodules-commit-info]
      (let [submodule-name (extract-submodule-name submodule)
            target-dir (common/bare-repo submodule-root submodule-name)
            server-repo-url (str server-repo-url "/" submodule-name)
            clone? (not (non-empty-dir? target-dir))
            status {submodule (apply-updates-to-repo
                                submodule-name
                                server-repo-url
                                commit
                                target-dir)}]
        (when clone?
          (jgit-utils/change-submodule-url! parent-repo submodule (str target-dir)))
        status))))

(defn process-submodules-for-repo
  [server-repo-url submodules-commit-info submodule-root parent-target parent-status]
  (let [parent-repo (jgit-utils/get-repository-from-git-dir parent-target)
        submodules-status (process-submodules-for-updates
                            server-repo-url
                            submodules-commit-info
                            submodule-root
                            parent-repo)]
    (assoc parent-status :submodules submodules-status)))

(defn process-repo-for-updates
  "Process a repository for any possible updates which may need to be applied.
  'server-repo-url' is the base URL at which the repository is hosted on the
  server.  'name' is the name of the repository.  'target-dir' is the location in
  which the client repository is intended to reside.  'latest-commits-info' is
  the commit id of the latest commit in the server repo. Returns status information
  about the sync process for the repo."
  [server-repo-url repo-name target-dir submodule-root latest-commits-info]
  (let [latest-commit-id (:commit latest-commits-info)
        submodules-commit-info (:submodules latest-commits-info)
        server-repo-url (str server-repo-url "/" (name repo-name))
        target-dir (fs/file target-dir)
        parent-status (apply-updates-to-repo
                        repo-name
                        server-repo-url
                        latest-commit-id
                        target-dir)
        status (if submodules-commit-info
                 (process-submodules-for-repo
                   server-repo-url
                   submodules-commit-info
                   submodule-root
                   target-dir
                   parent-status)
                 parent-status)]
    status))

(schema/defn process-repos-for-updates* :- RepoStates
  "Process the repositories for any updates which may be available on the server."
  [repos :- ReposConfig
   repo-base-url :- String
   latest-commits :- common/LatestCommitsPayload
   data-dir]
  (log/debugf "File sync latest commits from server: %s" latest-commits)
  (into {}
        (for [repo-name repos]
          (let [target-dir (common/bare-repo data-dir repo-name)
                submodule-root (str data-dir "/" (name repo-name))]
            (if (contains? latest-commits repo-name)
              (let [latest-commit (latest-commits repo-name)]
                (try+
                  {repo-name (process-repo-for-updates
                               repo-base-url
                               repo-name
                               target-dir
                               submodule-root
                               latest-commit)}
                  (catch sync-error? e
                    (log/errorf
                      (str "Error syncing repo: " (:message e))
                      repo-name)
                    {repo-name {:status :failed
                                :cause  e}})))
              (log/errorf
                "File sync did not find matching server repo for client repo: %s"
                repo-name))))))

(schema/defn process-callbacks!
  "Using the states of the repos managed by the client, call the registered
  callback function if necessary"
  [callbacks :- Callbacks
   repos-status :- RepoStates]
   (let [successful-repos (filter #(= :synced (get-in repos-status [% :status]))
                            (keys repos-status))
         necessary-callbacks (reduce set/union
                               (for [repo successful-repos]
                                 (get callbacks repo)))]
     (doseq [callback necessary-callbacks]
       (let [repos (set (filter
                          #(contains? (get callbacks %) callback)
                          (keys callbacks)))
             statuses (select-keys repos-status repos)]
         (log/debugf "Invoking callback function on repos %s"
           repos)
         (callback statuses)))))

(schema/defn process-repos-for-updates :- RepoStates
  [repos :- ReposConfig
   repo-base-url :- String
   latest-commits :- common/LatestCommitsPayload
   callbacks :- Callbacks
   data-dir]
  (let [repos-status (process-repos-for-updates* repos repo-base-url latest-commits data-dir)]
    (when-not (empty? callbacks)
      (process-callbacks! callbacks repos-status))
    repos-status))

; This function is marked 'always-validate' to ensure that the agent is always
; left in a valid state.
(schema/defn ^:always-validate sync-on-agent :- AgentState
  "Runs the sync process on the agent."
  [agent-state
   config :- CoreConfig
   http-client
   callbacks :- Callbacks]
  (let [repos (get-in config [:client-config :repos])]
    (log/debug "File sync process running on repos " repos)
    (try+
      (let [server-repo-path (get-in config [:client-config :server-repo-path])
            server-api-path (get-in config [:client-config :server-api-path])
            server-url (:server-url config)
            data-dir (:data-dir config)
            latest-commits (get-latest-commits-from-server
                             http-client
                             (str server-url server-api-path)
                             agent-state)
            repo-states (process-repos-for-updates
                          repos
                          (str server-url server-repo-path)
                          latest-commits
                          callbacks
                          data-dir)
            full-success? (every? #(not= (:status %) :failed)
                                  (vals repo-states))]
        {:status (if full-success? :successful :partial-success)
         :repos  repo-states})
      (catch sync-error? error
        (let [message (str "File sync failure: " (:message error))]
          (if-let [cause (:cause error)]
            (log/error cause message)
            (log/error message)))
        {:status :failed
         :error  error}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate register-callback!
  "Given the client service's context, registers a callback function.
   Throws an exception if the callback is not a function."
  [context :- ClientContext
   repo-ids :- #{schema/Keyword}
   callback-fn :- IFn]
  (let [callbacks (deref (:callbacks context))
        new-callbacks (into {} (for [repo repo-ids]
                                  (if (contains? callbacks repo)
                                    {repo (conj (get callbacks repo) callback-fn)}
                                    {repo #{callback-fn}})))]
    (swap! (:callbacks context) merge new-callbacks)))

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
   config :- CoreConfig
   http-client :- (schema/protocol http-client/HTTPClient)
   callbacks :- Callbacks]
  (let [periodic-sync (fn [& args]
                        (-> (apply sync-on-agent args)
                            (assoc :schedule-next-run? true)))
        send-to-agent #(send-off sync-agent periodic-sync config
                                 http-client callbacks)]
    (add-watch sync-agent
               ::schedule-watch
               (fn [key* ref* old-state new-state]
                 (when (:schedule-next-run? new-state)
                   (log/debug "Scheduling the next iteration of the sync process.")
                   (schedule-fn send-to-agent))))
    ; The watch is in place, now send the initial action.
    (send-to-agent)))

(schema/defn ^:always-validate sync-working-dir!
  "Synchronizes the contents of the working directory specified by
  working-dir with the most recent contents of the bare repo specified by
  repo-id"
  [data-dir :- schema/Str
   repos :- ReposConfig
   repo-id :- schema/Keyword
   working-dir :- schema/Str]
  (when-not (fs/exists? working-dir)
    (throw
      (IllegalStateException.
        (str "Directory " working-dir " must exist on disk to be synced "
             "as a working directory"))))
  (if (some #(= repo-id %) repos)
    (let [git-dir (common/bare-repo data-dir repo-id)
          repo (jgit-utils/get-repository git-dir working-dir)]
      (log/info (str "Syncing working directory at " working-dir
                     " for repository " repo-id))
      (jgit-utils/hard-reset repo)
      (jgit-utils/submodule-update repo))
    (throw
      (IllegalArgumentException.
        (str "No repository exists with id " repo-id)))))
