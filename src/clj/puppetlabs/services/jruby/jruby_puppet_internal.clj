(ns puppetlabs.services.jruby.jruby-puppet-internal
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [me.raynes.fs :as fs]
            [clojure.tools.logging :as log])
  (:import (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet)
           (com.puppetlabs.puppetserver.pool JRubyPool)
           (puppetlabs.services.jruby.jruby_puppet_schemas JRubyPuppetInstance PoisonPill)
           (java.util HashMap)
           (org.jruby CompatVersion Main RubyInstanceConfig RubyInstanceConfig$CompileMode)
           (org.jruby.embed ScriptingContainer LocalContextScope)
           (java.util.concurrent TimeUnit)
           (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def ruby-code-dir
  "The name of the directory containing the ruby code in this project.

  This directory is relative to `src/ruby` and works from source because the
  `src/ruby` directory is defined as a resource in `project.clj` which places
  the directory on the classpath which in turn makes the directory available on
  the JRuby load path.  Similarly, this works from the uberjar because this
  directory is placed into the root of the jar structure which is on the
  classpath.

  See also:  http://jruby.org/apidocs/org/jruby/runtime/load/LoadService.html"
  "puppet-server-lib")

(def compile-mode
  "The JRuby compile mode to use for all ruby components, e.g. the master
  service and CLI tools."
  RubyInstanceConfig$CompileMode/OFF)

(def compat-version
  "The JRuby compatibility version to use for all ruby components, e.g. the
  master service and CLI tools."
  (CompatVersion/RUBY1_9))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def JRubyPuppetInternalBorrowResult
  (schema/pred (some-fn nil?
                 jruby-schemas/poison-pill?
                 jruby-schemas/retry-poison-pill?
                 jruby-schemas/jruby-puppet-instance?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn get-system-env :- jruby-schemas/EnvPersistentMap
  "Same as System/getenv, but returns a clojure persistent map instead of a
  Java unmodifiable map."
  []
  (into {} (System/getenv)))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRubyPuppet's."
  [size]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (JRubyPool. size))

(schema/defn ^:always-validate managed-environment :- jruby-schemas/EnvMap
  "The environment variables that should be passed to the Puppet JRuby
  interpreters.

  We don't want them to read any ruby environment variables, like $RUBY_LIB or
  anything like that, so pass it an empty environment map - except - Puppet
  needs HOME and PATH for facter resolution, so leave those, along with GEM_HOME
  which is necessary for third party extensions that depend on gems.

  We need to set the JARS..REQUIRE variables in order to instruct JRuby's
  'jar-dependencies' to not try to load any dependent jars.  This is being
  done specifically to avoid JRuby trying to load its own version of Bouncy
  Castle, which may not the same as the one that 'puppetlabs/ssl-utils'
  uses. JARS_NO_REQUIRE was the legacy way to turn off jar loading but is
  being phased out in favor of JARS_REQUIRE.  As of JRuby 1.7.20, only
  JARS_NO_REQUIRE is honored.  Setting both of those here for forward
  compatibility."
  [env :- jruby-schemas/EnvMap
   gem-home :- schema/Str]
  (let [whitelist ["HOME" "PATH"]
        clean-env (select-keys env whitelist)]
    (assoc clean-env
      "GEM_HOME" gem-home
      "JARS_NO_REQUIRE" "true"
      "JARS_REQUIRE" "false")))

(schema/defn ^:always-validate managed-load-path :- [schema/Str]
  "Return a list of ruby LOAD_PATH directories built from the
  user-configurable ruby-load-path setting of the jruby-puppet configuration."
  [ruby-load-path :- [schema/Str]]
  (cons ruby-code-dir ruby-load-path))

(schema/defn ^:always-validate init-jruby-config :- jruby-schemas/ConfigurableJRuby
  "Applies configuration to a JRuby... thing.  See comments in `ConfigurableJRuby`
  schema for more details."
  [jruby-config :- jruby-schemas/ConfigurableJRuby
   ruby-load-path :- [schema/Str]
   gem-home :- schema/Str]
  (doto jruby-config
    (.setLoadPaths (managed-load-path ruby-load-path))
    (.setCompatVersion compat-version)
    (.setCompileMode compile-mode)
    (.setEnvironment (managed-environment (get-system-env) gem-home))))

(defn empty-scripting-container
  "Creates a clean instance of `org.jruby.embed.ScriptingContainer` with no code loaded."
  [ruby-load-path gem-home]
  {:pre [(sequential? ruby-load-path)
         (every? string? ruby-load-path)
         (string? gem-home)]
   :post [(instance? ScriptingContainer %)]}
  (-> (ScriptingContainer. LocalContextScope/SINGLETHREAD)
      (init-jruby-config ruby-load-path gem-home)))

(defn create-scripting-container
  "Creates an instance of `org.jruby.embed.ScriptingContainer` and loads up the
  puppet and facter code inside it."
  [ruby-load-path gem-home]
  {:pre [(sequential? ruby-load-path)
         (every? string? ruby-load-path)
         (string? gem-home)]
   :post [(instance? ScriptingContainer %)]}
  ;; for information on other legal values for `LocalContextScope`, there
  ;; is some documentation available in the JRuby source code; e.g.:
  ;; https://github.com/jruby/jruby/blob/1.7.11/core/src/main/java/org/jruby/embed/LocalContextScope.java#L58
  ;; I'm convinced that this is the safest and most reasonable value
  ;; to use here, but we could potentially explore optimizations in the future.
  (doto (empty-scripting-container ruby-load-path gem-home)
    ;; As of JRuby 1.7.20 (and the associated 'jruby-openssl' it pulls in),
    ;; we need to explicitly require 'jar-dependencies' so that it is used
    ;; to manage jar loading.  We do this so that we can instruct
    ;; 'jar-dependencies' to not actually load any jars.  See the environment
    ;; variable configuration in 'init-jruby-config' for more
    ;; information.
    (.runScriptlet "require 'jar-dependencies'")
    (.runScriptlet "require 'puppet/server/master'")))

(schema/defn ^:always-validate config->puppet-config :- HashMap
  "Given the raw jruby-puppet configuration section, return a
  HashMap with the configuration necessary for ruby Puppet."
  [config :- jruby-schemas/JRubyPuppetConfig]
  (let [puppet-config (new HashMap)]
    (doseq [[setting dir] [[:master-conf-dir "confdir"]
                           [:master-code-dir "codedir"]
                           [:master-var-dir "vardir"]
                           [:master-run-dir "rundir"]
                           [:master-log-dir "logdir"]]]
      (if-let [value (get config setting)]
        (.put puppet-config dir (fs/absolute-path value))))
    puppet-config))

(schema/defn borrow-with-timeout-fn :- JRubyPuppetInternalBorrowResult
  [timeout :- schema/Int
   pool :- jruby-schemas/pool-queue-type]
  (.borrowItemWithTimeout pool timeout TimeUnit/MILLISECONDS))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-pool-from-config :- jruby-schemas/PoolState
  "Create a new PoolState based on the config input."
  [{size :max-active-instances} :- jruby-schemas/JRubyPuppetConfig]
  {:pool (instantiate-free-pool size)
   :size size})

(schema/defn ^:always-validate
  create-pool-instance! :- JRubyPuppetInstance
  "Creates a new JRubyPuppet instance and adds it to the pool."
  [pool     :- jruby-schemas/pool-queue-type
   id       :- schema/Int
   config   :- jruby-schemas/JRubyPuppetConfig
   flush-instance-fn :- IFn
   profiler :- (schema/maybe PuppetProfiler)]
  (let [{:keys [ruby-load-path gem-home
                http-client-ssl-protocols http-client-cipher-suites
                http-client-connect-timeout-milliseconds
                http-client-idle-timeout-milliseconds
                use-legacy-auth-conf]} config]
    (when-not ruby-load-path
      (throw (Exception.
               "JRuby service missing config value 'ruby-load-path'")))
    (let [scripting-container   (create-scripting-container ruby-load-path gem-home)
          env-registry          (puppet-env/environment-registry)
          ruby-puppet-class     (.runScriptlet scripting-container "Puppet::Server::Master")
          puppet-config         (config->puppet-config config)
          puppet-server-config  (HashMap.)]
      (when http-client-ssl-protocols
        (.put puppet-server-config "ssl_protocols" (into-array String http-client-ssl-protocols)))
      (when http-client-cipher-suites
        (.put puppet-server-config "cipher_suites" (into-array String http-client-cipher-suites)))
      (.put puppet-server-config "profiler" profiler)
      (.put puppet-server-config "environment_registry" env-registry)
      (.put puppet-server-config "http_connect_timeout_milliseconds"
        http-client-connect-timeout-milliseconds)
      (.put puppet-server-config "http_idle_timeout_milliseconds"
        http-client-idle-timeout-milliseconds)
      (.put puppet-server-config "use_legacy_auth_conf" use-legacy-auth-conf)
      (let [instance (jruby-schemas/map->JRubyPuppetInstance
                       {:pool                 pool
                        :id                   id
                        :max-requests         (:max-requests-per-instance config)
                        :flush-instance-fn    flush-instance-fn
                        :state                (atom {:borrow-count 0})
                        :jruby-puppet         (.callMethod scripting-container
                                                           ruby-puppet-class
                                                           "new"
                                                           (into-array Object
                                                                       [puppet-config puppet-server-config])
                                                           JRubyPuppet)
                        :scripting-container  scripting-container
                        :environment-registry env-registry})]
        (.register pool instance)
        instance))))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  @(:pool-state context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRubyPuppet pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (:pool (get-pool-state context)))

(schema/defn ^:always-validate
  get-pool-size :- schema/Int
  "Gets the size of the JRubyPuppet pool from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:config :max-active-instances]))

(schema/defn borrow-without-timeout-fn :- JRubyPuppetInternalBorrowResult
  [pool :- jruby-schemas/pool-queue-type]
  (.borrowItem pool))

(schema/defn borrow-from-pool!* :- jruby-schemas/JRubyPuppetBorrowResult
  "Given a borrow function and a pool, attempts to borrow a JRuby instance from a pool.
  If successful, updates the state information and returns the JRuby instance.
  Returns nil if the borrow function returns nil; throws an exception if
  the borrow function's return value indicates an error condition."
  [borrow-fn :- (schema/pred ifn?)
   pool :- jruby-schemas/pool-queue-type]
  (let [instance (borrow-fn pool)]
    (cond (instance? PoisonPill instance)
          (do
            (.releaseItem pool instance)
            (throw (IllegalStateException.
                     "Unable to borrow JRuby instance from pool"
                     (:err instance))))

          (jruby-schemas/jruby-puppet-instance? instance)
          instance

          ((some-fn nil? jruby-schemas/retry-poison-pill?) instance)
          instance

          :else
          (throw (IllegalStateException.
                   (str "Borrowed unrecognized object from pool!: " instance))))))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyPuppetInstanceOrRetry
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext]
  (borrow-from-pool!* borrow-without-timeout-fn
                      (get-pool pool-context)))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- jruby-schemas/JRubyPuppetBorrowResult
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (borrow-from-pool!* (partial borrow-with-timeout-fn timeout)
                      (get-pool pool-context)))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyPuppetInstanceOrRetry]
  (if (jruby-schemas/jruby-puppet-instance? instance)
    (let [new-state (swap! (:state instance)
                           update-in [:borrow-count] inc)
          {:keys [max-requests flush-instance-fn pool]} instance]
      (if (and (pos? max-requests)
               (>= (:borrow-count new-state) max-requests))
        (do
          (log/infof (str "Flushing JRuby instance %s because it has exceeded the "
                          "maximum number of requests (%s)")
                     (:id instance)
                     max-requests)
          (try
            (flush-instance-fn pool instance)
            (finally
              (.releaseItem pool instance false))))
        (.releaseItem pool instance)))
    ;; if we get here, it was from a borrow and we got a Retry, so we just
    ;; return it to the pool.
    (.releaseItem (:pool instance) instance)))

(schema/defn ^:always-validate new-main :- jruby-schemas/JRubyMain
  "Return a new JRuby Main instance which should only be used for CLI purposes,
  e.g. for the ruby, gem, and irb subcommands.  Internal core services should
  use `create-scripting-container` instead of `new-main`."
  [config :- jruby-schemas/JRubyPuppetConfig]
  (let [{:keys [ruby-load-path gem-home]} config
        jruby-config (init-jruby-config
                      (RubyInstanceConfig.)
                      ruby-load-path
                      gem-home)]
    (Main. jruby-config)))
