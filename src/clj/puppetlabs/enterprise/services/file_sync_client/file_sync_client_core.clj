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
            [puppetlabs.http.client.common :as http-client]
            [puppetlabs.trapperkeeper.services.status.status-core :as status])
  (:import (org.eclipse.jgit.transport HttpTransport)
           (clojure.lang IFn Agent Atom)
           (java.io IOException)
           (org.eclipse.jgit.api.errors GitAPIException)
           (org.eclipse.jgit.transport.http HttpConnectionFactory)
           (com.puppetlabs.enterprise HttpClientConnection)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Schemas

(def ClientContext
  "A schema describing the service context for the File Sync Client service"
  {:callbacks Atom
   :agent Agent})

(def Config
  "Schema defining the full content of the file sync client service
  configuration.

  The keys should have the following values:

    * :poll-interval - Number of seconds which the file sync client service
                       should wait between attempts to poll the server for
                       latest available content."
  {:poll-interval                     schema/Int
   :server-repo-path                  schema/Str
   :server-api-path                   schema/Str
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
     :latest_commit (schema/maybe schema/Str)
     (schema/optional-key :submodules) {schema/Str (schema/recursive #'SingleRepoState)}}))

(def RepoStates
  "A schema which describes a map containing valid states for
  numerous repos after a sync"
  {schema/Keyword SingleRepoState})

(def StatusData
  "A schema which describes a map containing status data for the client
  status endpoint"
  {:last_check_in (schema/maybe
                    {:timestamp schema/Str
                     :response common/LatestCommitsPayload})
   :last_successful_sync_time (schema/maybe schema/Str)})

(def AgentState
  "A schema which describes a valid state of the agent."
  (schema/conditional
    #(= (:status %) :failed)
    {:status (schema/eq :failed)
     :error SyncError
     :status-data StatusData
     (schema/optional-key :schedule-next-run?) Boolean}

    #(= (:status %) :created)
    {:status (schema/eq :created)
     :status-data StatusData}

    :else
    {:status (schema/enum :successful :partial-success)
     :repos RepoStates
     :status-data StatusData
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

(schema/defn ^:always-validate create-connection :- HttpClientConnection
  [ssl-ctxt :- common/SSLContextOrNil
   url connection-proxy]
  (HttpClientConnection. ssl-ctxt url connection-proxy))

(schema/defn ^:always-validate create-connection-factory :- HttpConnectionFactory
  [ssl-ctxt :- common/SSLContextOrNil]
  (proxy [HttpConnectionFactory] []
    (create
      ([url]
       (create-connection ssl-ctxt (.toString url) nil))
      ([url connection-proxy]
       (create-connection ssl-ctxt (.toString url) connection-proxy)))))

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
  (with-open [repo (jgit-utils/get-repository-from-git-dir target-dir)]
    (jgit-utils/validate-repo-exists! repo)
    (when-not (= latest-commit-id (jgit-utils/head-rev-id repo))
      (log/infof "Fetching '%s' to %s" name latest-commit-id)
      (jgit-utils/fetch repo))))

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
         :latest_commit current-commit-id})
      (catch GitAPIException e
        (throw+ {:type    ::error
                 :message (message-with-repo-info
                            (str "File sync was unable to "
                              (if fetch? "fetch" "clone")
                              " from server repo")
                            name
                            target-dir)
                 :cause   e})))))

(defn process-submodules-for-updates
  "Process a repo's submodules for any possible updates which may need to be
  applied."
  [server-repo-url submodules-commit-info submodule-root parent-repo]
  (into
    {}
    (for [[submodule commit] submodules-commit-info]
      (let [submodule-name (common/extract-submodule-name submodule)
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
  (with-open [parent-repo (jgit-utils/get-repository-from-git-dir parent-target)]
    (let [submodules-status (process-submodules-for-updates
                              server-repo-url
                              submodules-commit-info
                              submodule-root
                              parent-repo)]
      (assoc parent-status :submodules submodules-status))))

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
  [repos :- [schema/Keyword]
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
  [repos :- [schema/Keyword]
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
  (try+
    (let [server-repo-path (get-in config [:client-config :server-repo-path])
          server-api-path (get-in config [:client-config :server-api-path])
          server-url (:server-url config)
          data-dir (:data-dir config)
          latest-commits (get-latest-commits-from-server
                           http-client
                           (str server-url server-api-path)
                           agent-state)
          check-in-time (common/timestamp)
          repos (keys latest-commits)
          _ (log/debug "File sync process running on repos " repos)
          repo-states (process-repos-for-updates
                        repos
                        (str server-url server-repo-path)
                        latest-commits
                        callbacks
                        data-dir)
          full-success? (every? #(not= (:status %) :failed)
                          (vals repo-states))
          sync-time (common/timestamp)
          status-data (assoc (:status-data agent-state) :last_check_in {:timestamp check-in-time
                                                                        :response latest-commits})]
      {:status (if full-success? :successful :partial-success)
       :repos repo-states
       :status-data (if full-success?
                      (assoc status-data :last_successful_sync_time sync-time)
                      status-data)})
    (catch sync-error? error
      (let [message (str "File sync failure: " (:message error))]
        (if-let [cause (:cause error)]
          (log/error cause message)
          (log/error message)))
      {:status :failed
       :error error
       :status-data (:status-data agent-state)})))

(defn get-commit-status
  [path]
  (with-open [repo (jgit-utils/get-repository-from-git-dir path)]
    ;; Return nil for the commit status if the repo has never been
    ;; synced or the repo does not yet have any commits
    (when (jgit-utils/repo-exists? repo)
      (common/repo->latest-commit-status-info repo))))

(defn repos-status
  [repos data-dir latest-commits]
  (into {}
    (for [repo-id repos]
      (let [submodules (get-in latest-commits [repo-id :submodules])]
        {repo-id {:latest_commit (get-commit-status (common/bare-repo data-dir repo-id))
                  :submodules (into {}
                                (for [[submodule _] submodules]
                                  (let [path (common/submodule-bare-repo
                                               data-dir
                                               repo-id
                                               (common/extract-submodule-name submodule))]
                                    {submodule (get-commit-status path)})))}}))))

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
    (create-connection-factory)
    (HttpTransport/setConnectionFactory)))

(defn create-agent
  "Creates and returns the agent used by the file sync client service.
  'request-shutdown' is a function that will be called in the event of a fatal
  error during the sync process when the entire applications needs to be shut down."
  [request-shutdown]
  (agent
    {:status :created
     :status-data {:last_check_in nil
                   :last_successful_sync_time nil}}
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
   repo-id :- schema/Keyword
   working-dir :- common/StringOrFile]
  (when-not (fs/exists? working-dir)
    (when-not (fs/mkdirs working-dir)
      (throw
        (IllegalStateException.
          (str "Directory " working-dir " does not exist and could not be created."
            "It must exist on disk to be sync'ed as a working directory.")))))
  (let [git-dir (common/bare-repo data-dir repo-id)]
    (if-not (fs/exists? git-dir)
      (throw
        (IllegalArgumentException.
          (str "No repository exists with id " repo-id)))
      (with-open [repo (jgit-utils/get-repository git-dir working-dir)]
        (log/info (str "Syncing working directory at " working-dir
                    " for repository " repo-id))
        (jgit-utils/hard-reset repo)
        (jgit-utils/submodule-update repo)))))

(schema/defn ^:always-validate status :- status/StatusCallbackResponse
  [level :- schema/Keyword
   data-dir :- schema/Str
   status-data]
  (let [latest-commits (get-in status-data [:last_check_in :response])
        repos (keys latest-commits)
        status-data (if repos
                      (assoc status-data :repos (repos-status repos data-dir latest-commits))
                      status-data)]
    {:state :running
     :status (when (not= level :critical)
               (assoc status-data
                 :timestamp (common/timestamp)))}))

(schema/defn ^:always-validate get-working-dir-status
  "Returns the status of the working dir for a specified repo, along with
  the status of all submodules"
  [data-dir :- schema/Str
   repo-id :- schema/Keyword
   working-dir :- common/StringOrFile]
  (when (fs/exists? working-dir)
    (let [git-dir (common/bare-repo data-dir repo-id)]
      (if-not (fs/exists? git-dir)
        (throw
          (IllegalArgumentException.
            (str "No repository exists with id " repo-id)))
        (with-open [repo (jgit-utils/get-repository git-dir working-dir)]
          {:status (common/working-dir-status-info repo)
           :submodules (common/submodules-status-info repo)})))))
