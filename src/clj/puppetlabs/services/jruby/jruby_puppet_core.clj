(ns puppetlabs.services.jruby.jruby-puppet-core
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.kitchensink.classpath :as ks-classpath]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import (puppetlabs.services.jruby.jruby_puppet_schemas JRubyPuppetInstance)
           (com.puppetlabs.puppetserver PuppetProfiler)
           (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-jruby-compile-mode
  "Default value for JRuby's 'CompileMode' setting."
  :off)

(def default-borrow-timeout
  "Default timeout when borrowing instances from the JRuby pool in
   milliseconds. Current value is 1200000ms, or 20 minutes."
  1200000)

(def default-http-connect-timeout
  "The default number of milliseconds that the client will wait for a connection
  to be established. Currently set to 2 minutes."
  (* 2 60 1000))

(def default-http-socket-timeout
  "The default number of milliseconds that the client will allow for no data to
  be available on the socket. Currently set to 20 minutes."
  (* 20 60 1000))

(def default-master-conf-dir
  "/etc/puppetlabs/puppet")

(def default-master-code-dir
  "/etc/puppetlabs/code")

(def default-master-log-dir
  "/var/log/puppetlabs/puppetserver")

(def default-master-run-dir
  "/var/run/puppetlabs/puppetserver")

(def default-master-var-dir
  "/opt/puppetlabs/server/data/puppetserver")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn default-pool-size
  "Calculate the default size of the JRuby pool, based on the number of cpus."
  [num-cpus]
  (->> (- num-cpus 1)
       (max 1)
       (min 4)))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  (jruby-internal/get-pool-state context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRubyPuppet pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (jruby-internal/get-pool context))

(schema/defn ^:always-validate
  registered-instances :- [JRubyPuppetInstance]
  [context :- jruby-schemas/PoolContext]
  (-> (get-pool context)
      .getRegisteredElements
      .iterator
      iterator-seq
      vec))

(defn verify-config-found!
  [config]
  (if (or (not (map? config))
          (empty? config))
    (throw (IllegalArgumentException. (str "No configuration data found.  Perhaps "
                                           "you did not specify the --config option?")))))

(schema/defn create-requested-event :- jruby-schemas/JRubyRequestedEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :instance-requested
   :reason reason})

(schema/defn create-borrowed-event :- jruby-schemas/JRubyBorrowedEvent
  [requested-event :- jruby-schemas/JRubyRequestedEvent
   instance :- jruby-schemas/JRubyPuppetBorrowResult]
  {:type :instance-borrowed
   :reason (:reason requested-event)
   :requested-event requested-event
   :instance instance})

(schema/defn create-returned-event :- jruby-schemas/JRubyReturnedEvent
  [instance :- jruby-schemas/JRubyPuppetInstanceOrPill
   reason :- jruby-schemas/JRubyEventReason]
  {:type :instance-returned
   :reason reason
   :instance instance})

(schema/defn create-lock-requested-event :- jruby-schemas/JRubyLockRequestedEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :lock-requested
   :reason reason})

(schema/defn create-lock-acquired-event :- jruby-schemas/JRubyLockAcquiredEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :lock-acquired
   :reason reason})

(schema/defn create-lock-released-event :- jruby-schemas/JRubyLockReleasedEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :lock-released
   :reason reason})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Support functions for event notification

(schema/defn notify-event-listeners :- jruby-schemas/JRubyEvent
  [event-callbacks :- [IFn]
   event :- jruby-schemas/JRubyEvent]
  (doseq [f event-callbacks]
    (f event))
  event)

(schema/defn instance-requested :- jruby-schemas/JRubyRequestedEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-requested-event reason)))

(schema/defn instance-borrowed :- jruby-schemas/JRubyBorrowedEvent
  [event-callbacks :- [IFn]
   requested-event :- jruby-schemas/JRubyRequestedEvent
   instance :- jruby-schemas/JRubyPuppetBorrowResult]
  (notify-event-listeners event-callbacks (create-borrowed-event requested-event instance)))

(schema/defn instance-returned :- jruby-schemas/JRubyReturnedEvent
  [event-callbacks :- [IFn]
   instance :- jruby-schemas/JRubyPuppetInstanceOrPill
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-returned-event instance reason)))

(schema/defn lock-requested :- jruby-schemas/JRubyLockRequestedEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-lock-requested-event reason)))

(schema/defn lock-acquired :- jruby-schemas/JRubyLockAcquiredEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-lock-acquired-event reason)))

(schema/defn lock-released :- jruby-schemas/JRubyLockReleasedEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-lock-released-event reason)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  initialize-config :- jruby-schemas/JRubyPuppetConfig
  [config :- {schema/Keyword schema/Any}]
  (-> (get-in config [:jruby-puppet])
      (assoc :http-client-ssl-protocols
             (get-in config [:http-client :ssl-protocols]))
      (assoc :http-client-cipher-suites
             (get-in config [:http-client :cipher-suites]))
      (assoc :http-client-connect-timeout-milliseconds
             (get-in config [:http-client :connect-timeout-milliseconds]
                     default-http-connect-timeout))
      (assoc :http-client-idle-timeout-milliseconds
             (get-in config [:http-client :idle-timeout-milliseconds]
                     default-http-socket-timeout))
      (update-in [:compile-mode] #(keyword (or % default-jruby-compile-mode)))
      (update-in [:borrow-timeout] #(or % default-borrow-timeout))
      (update-in [:master-conf-dir] #(or % default-master-conf-dir))
      (update-in [:master-var-dir] #(or % default-master-var-dir))
      (update-in [:master-code-dir] #(or % default-master-code-dir))
      (update-in [:master-run-dir] #(or % default-master-run-dir))
      (update-in [:master-log-dir] #(or % default-master-log-dir))
      (update-in [:max-active-instances] #(or % (default-pool-size (ks/num-cpus))))
      (update-in [:max-requests-per-instance] #(or % 0))
      (update-in [:use-legacy-auth-conf] #(or % (nil? %)))
      (dissoc :environment-class-cache-enabled)))

(def facter-jar
  "Well-known name of the facter jar file"
  "facter.jar")

(schema/defn ^:always-validate
  add-facter-jar-to-system-classloader
  "Searches the ruby load path for a file whose name matches that of the
  facter jar file.  The first one found is added to the system classloader's
  classpath.  If no match is found, an info message is written to the log
  but no failure is returned"
  [ruby-load-path :- (schema/both (schema/pred vector?) [schema/Str]) ]
  (if-let [facter-jar (first
                        (filter fs/exists?
                          (map #(fs/file % facter-jar) ruby-load-path)))]
    (do
      (log/debugf "Adding facter jar to classpath from: %s" facter-jar)
      (ks-classpath/add-classpath facter-jar))
    (log/info "Facter jar not found in ruby load path")))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRubyPuppet pool context with an empty pool. Once the JRubyPuppet
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)
   agent-shutdown-fn :- (schema/pred ifn?)]
  {:config                config
   :profiler              profiler
   :pool-agent            (jruby-agents/pool-agent agent-shutdown-fn)
   ;; For an explanation of why we need a separate agent for the `flush-instance`,
   ;; see the comments in jruby-puppet-agents/send-flush-instance
   :flush-instance-agent  (jruby-agents/pool-agent agent-shutdown-fn)
   :pool-state            (atom (jruby-internal/create-pool-from-config config))})

(schema/defn ^:always-validate
  free-instance-count
  "Returns the number of JRubyPuppet instances available in the pool."
  [pool :- jruby-schemas/pool-queue-type]
  {:post [(>= % 0)]}
  (.size pool))

(schema/defn ^:always-validate
  instance-state :- jruby-schemas/JRubyInstanceState
  "Get the state metadata for a JRubyPuppet instance."
  [jruby-puppet :- (schema/pred jruby-schemas/jruby-puppet-instance?)]
  @(:state jruby-puppet))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyPuppetInstanceOrPill
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (let [requested-event (instance-requested event-callbacks reason)
        instance (jruby-internal/borrow-from-pool pool-context)]
    (instance-borrowed event-callbacks requested-event instance)
    instance))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- jruby-schemas/JRubyPuppetBorrowResult
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int
   reason :- schema/Any
   event-callbacks :- [IFn]]
  {:pre  [(>= timeout 0)]}
  (let [requested-event (instance-requested event-callbacks reason)
        instance (jruby-internal/borrow-from-pool-with-timeout
                   pool-context
                   timeout)]
    (instance-borrowed event-callbacks requested-event instance)
    instance))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyPuppetInstanceOrPill
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (instance-returned event-callbacks instance reason)
  (jruby-internal/return-to-pool instance))

(schema/defn ^:always-validate
  lock-pool
  "Locks the JRuby pool for exclusive access."
  [pool :- jruby-schemas/pool-queue-type
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (log/debug "Acquiring lock on JRubyPool...")
  (lock-requested event-callbacks reason)
  (.lock pool)
  (lock-acquired event-callbacks reason)
  (log/debug "Lock acquired"))

(schema/defn ^:always-validate
  unlock-pool
  "Unlocks the JRuby pool, restoring concurernt access."
  [pool :- jruby-schemas/pool-queue-type
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (.unlock pool)
  (lock-released event-callbacks reason)
  (log/debug "Lock on JRubyPool released"))

(schema/defn ^:always-validate cli-ruby! :- jruby-schemas/JRubyMainStatus
  "Run JRuby as though native `ruby` were invoked with args on the CLI"
  [config :- {schema/Keyword schema/Any}
   args :- [schema/Str]]
  (let [main (jruby-internal/new-main (initialize-config config))
        argv (into-array String (concat ["-rjar-dependencies"] args))]
    (.run main argv)))

(schema/defn ^:always-validate cli-run! :- (schema/maybe jruby-schemas/JRubyMainStatus)
  "Run a JRuby CLI command, e.g. gem, irb, etc..."
  [config :- {schema/Keyword schema/Any}
   command :- schema/Str
   args :- [schema/Str]]
  (let [bin-dir "META-INF/jruby.home/bin"
        load-path (format "%s/%s" bin-dir command)
        url (io/resource load-path (.getClassLoader org.jruby.Main))]
    (if url
      (cli-ruby! config
        (concat ["-e" (format "load '%s'" url) "--"] args))
      (log/errorf "command %s could not be found in %s" command bin-dir))))

(def EnvironmentClassInfoCacheEntry
  "Data structure that holds per-environment cache information for the
  environment_classes info cache"
  {:tag (schema/maybe schema/Str)
   :cache-generation-id schema/Int})

(def EnvironmentClassInfoCache
  "Data structure for the environment_classes info cache"
  {schema/Str EnvironmentClassInfoCacheEntry})

(schema/defn ^:always-validate environment-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Create an environment class info entry.  The return value will have a nil
  tag and cache-generation-id set to 1."
  []
  {:tag nil
   :cache-generation-id 1})

(schema/defn ^:always-validate inc-cache-generation-id-for-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Return the supplied 'original-environment-class-info-entry' class info entry,
  only updated with a cache-generation-id value that has been incremented.
  If the supplied parameter is nil, the return value will have a tag set to
  nil and cache-generation-id set to 1."
  [original-environment-class-info-entry :-
   (schema/maybe EnvironmentClassInfoCacheEntry)]
  (if original-environment-class-info-entry
    (update original-environment-class-info-entry :cache-generation-id inc)
    (environment-class-info-entry)))

(schema/defn ^:always-validate update-environment-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Return the supplied 'original-environment-class-info-entry', only updated
  with the supplied tag and a cache-generation-id value that has been
  incremented."
  [original-environment-class-info-entry :-
   (schema/maybe EnvironmentClassInfoCacheEntry)
   tag :- (schema/maybe schema/Str)]
  (-> original-environment-class-info-entry
      inc-cache-generation-id-for-class-info-entry
      (assoc :tag tag)))

(schema/defn ^:always-valid invalidated-environment-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Return the supplied 'original-environment-class-info-entry', only updated
  with a nil tag and a cache-generation-id value that has been incremented."
  [original-environment-class-info-entry :-
   (schema/maybe EnvironmentClassInfoCacheEntry)]
  (update-environment-class-info-entry
   original-environment-class-info-entry
   nil))

(schema/defn ^:always-validate
  environment-class-info-cache-with-invalidated-entry
  :- EnvironmentClassInfoCache
  "Return the supplied 'environment-class-info-cache', only updated with an
  invalidated entry for the supplied 'env-name'.  The invalidated entry will
  have nil tag and a cache-generation-id value that has been incremented."
  [environment-class-info-cache :- EnvironmentClassInfoCache
   env-name :- schema/Str]
  (->> env-name
       (get environment-class-info-cache)
       invalidated-environment-class-info-entry
       (assoc environment-class-info-cache env-name)))

(schema/defn ^:always-validate
  environment-class-info-cache-updated-with-tag :- EnvironmentClassInfoCache
  "Return the supplied environment class info cache argument, updated per
  supplied arguments.  cache-generation-id-before-tag-computed should represent
  what the client received for a
  'get-environment-class-info-cache-generation-id!' call for the environment,
  made before the client started doing the work to parse environment class info
  / compute the new tag.  If cache-generation-id-before-tag-computed equals the
  'cache-generation-id' value stored in the cache for the environment, the new
  'tag' will be stored for the environment and the corresponding
  'cache-generation-id' value will be incremented.  If
  cache-generation-id-before-tag-computed is different than the
  'cache-generation-id' value stored in the cache for the environment, the cache
  will remain unchanged as a result of this call."
  [environment-class-info-cache :- EnvironmentClassInfoCache
   env-name :- schema/Str
   tag :- (schema/maybe schema/Str)
   cache-generation-id-before-tag-computed :- schema/Int]
  (let [cache-entry (get environment-class-info-cache env-name)]
    (if (= (:cache-generation-id cache-entry)
           cache-generation-id-before-tag-computed)
      (assoc environment-class-info-cache
        env-name
        (update-environment-class-info-entry cache-entry tag))
      environment-class-info-cache)))

(schema/defn ^:always-validate
  add-environment-class-info-cache-entry-if-not-present!
  :- EnvironmentClassInfoCache
  "Update the 'environment-class-info-cache' atom with a new cache entry for
  the supplied 'env-name' (if no entry is already present) and return back
  the new value that the atom has been set to."
  [environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)
   env-name :- schema/Str]
  (swap! environment-class-info-cache
         #(if (contains? % env-name)
           %
           (assoc % env-name (environment-class-info-entry)))))

(schema/defn ^:always-validate
  get-environment-class-info-cache-generation-id! :- schema/Int
  "Get the current cache generation id for a specific environment's class info.
  If no entry for the environment had existed at the point this function was
  called this function would, as a side effect, populate a new entry for that
  environment into the cache."
  [environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)
   env-name :- schema/Str]
  (-> (add-environment-class-info-cache-entry-if-not-present!
       environment-class-info-cache
       env-name)
      (get-in [env-name :cache-generation-id])))

(schema/defn ^:always-validate
  mark-environment-expired!
  "Mark the specified environment expired, in all JRuby instances.  Resets
  the cached class info for the environment's 'tag' to nil and increments the
  'cache-generation-id' value."
  [context :- jruby-schemas/PoolContext
   env-name :- schema/Str
   environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)]
  (swap! environment-class-info-cache
         environment-class-info-cache-with-invalidated-entry
         env-name)
  (doseq [jruby-instance (registered-instances context)]
    (-> jruby-instance
        :environment-registry
        (puppet-env/mark-environment-expired! env-name))))

(schema/defn ^:always-validate
  mark-all-environments-expired!
  "Mark all cached environments expired, in all JRuby instances.  Resets the
  cached class info for all previously stored environment 'tags' to nil and
  increments the 'cache-generation-id' value."
  [context :- jruby-schemas/PoolContext
   environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)]
  (swap! environment-class-info-cache
         (partial ks/mapvals invalidated-environment-class-info-entry))
  (doseq [jruby-instance (registered-instances context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))
