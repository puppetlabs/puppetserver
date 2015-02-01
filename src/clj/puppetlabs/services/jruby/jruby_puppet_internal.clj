(ns puppetlabs.services.jruby.jruby-puppet-internal
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [me.raynes.fs :as fs]
            [clojure.tools.logging :as log])
  (:import (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet)
           (puppetlabs.services.jruby.jruby_puppet_schemas JRubyPuppetInstance PoisonPill)
           (java.util HashMap)
           (org.jruby CompatVersion RubyInstanceConfig$CompileMode)
           (org.jruby.embed ScriptingContainer LocalContextScope)
           (java.util.concurrent LinkedBlockingDeque TimeUnit)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def jruby-puppet-env
  "The environment variables that should be passed to the Puppet JRuby interpreters.
  We don't want them to read any ruby environment variables, like $GEM_HOME or
  $RUBY_LIB or anything like that, so pass it an empty environment map - except -
  Puppet needs HOME and PATH for facter resolution, so leave those."
  (select-keys (System/getenv) ["HOME" "PATH"]))

(def ruby-code-dir
  "The name of the directory containing the ruby code in this project.
  This directory lives under src/ruby/"
  "puppet-server-lib")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRubyPuppet's."
  [size]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (LinkedBlockingDeque. size))

(schema/defn ^:always-validate
  create-pool-from-config :- jruby-schemas/PoolState
  "Create a new PoolData based on the config input."
  [{size :max-active-instances} :- jruby-schemas/JRubyPuppetConfig]
  {:pool (instantiate-free-pool size)
   :size size})

(defn prep-scripting-container
  [scripting-container ruby-load-path gem-home]
  (doto scripting-container
    (.setLoadPaths (cons ruby-code-dir
                         (map fs/absolute-path ruby-load-path)))
    (.setCompatVersion (CompatVersion/RUBY1_9))
    (.setCompileMode RubyInstanceConfig$CompileMode/OFF)
    (.setEnvironment (merge {"GEM_HOME" gem-home
                             "JARS_NO_REQUIRE" "true"}
                            jruby-puppet-env))))

(defn empty-scripting-container
  "Creates a clean instance of `org.jruby.embed.ScriptingContainer` with no code loaded."
  [ruby-load-path gem-home]
  {:pre [(sequential? ruby-load-path)
         (every? string? ruby-load-path)
         (string? gem-home)]
   :post [(instance? ScriptingContainer %)]}
  (-> (ScriptingContainer. LocalContextScope/SINGLETHREAD)
      (prep-scripting-container ruby-load-path gem-home)))

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
    (.runScriptlet "require 'puppet/server/master'")))

(schema/defn ^:always-validate
  create-pool-instance! :- JRubyPuppetInstance
  "Creates a new JRubyPuppet instance and adds it to the pool."
  [pool     :- jruby-schemas/pool-queue-type
   id       :- schema/Int
   config   :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (let [{:keys [ruby-load-path gem-home master-conf-dir master-var-dir
                http-client-ssl-protocols http-client-cipher-suites]} config]
    (when-not ruby-load-path
      (throw (Exception.
               "JRuby service missing config value 'ruby-load-path'")))
    (let [scripting-container   (create-scripting-container ruby-load-path gem-home)
          env-registry          (puppet-env/environment-registry)
          ruby-puppet-class     (.runScriptlet scripting-container "Puppet::Server::Master")
          puppet-config         (HashMap.)
          puppet-server-config  (HashMap.)]
      (when master-conf-dir
        (.put puppet-config "confdir" (fs/absolute-path master-conf-dir)))
      (when master-var-dir
        (.put puppet-config "vardir" (fs/absolute-path master-var-dir)))

      (when http-client-ssl-protocols
        (.put puppet-server-config "ssl_protocols" (into-array String http-client-ssl-protocols)))
      (when http-client-cipher-suites
        (.put puppet-server-config "cipher_suites" (into-array String http-client-cipher-suites)))
      (.put puppet-server-config "profiler" profiler)
      (.put puppet-server-config "environment_registry" env-registry)

      (let [instance (jruby-schemas/map->JRubyPuppetInstance
                       {:pool                 pool
                        :id                   id
                        :state                (atom {:request-count 0})
                        :jruby-puppet         (.callMethod scripting-container
                                                           ruby-puppet-class
                                                           "new"
                                                           (into-array Object
                                                                       [puppet-config puppet-server-config])
                                                           JRubyPuppet)
                        :scripting-container  scripting-container
                        :environment-registry env-registry})]
        (.putLast pool instance)
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

(schema/defn borrow-without-timeout-fn :- jruby-schemas/JRubyPuppetBorrowResult
  [pool :- jruby-schemas/pool-queue-type]
  (.takeFirst pool))

(schema/defn borrow-with-timeout-fn :- jruby-schemas/JRubyPuppetBorrowResult
  [timeout :- schema/Int
   pool :- jruby-schemas/pool-queue-type]
  (.pollFirst pool timeout TimeUnit/MILLISECONDS))

(schema/defn borrowed-poison-pill
  [pool :- jruby-schemas/pool-queue-type
   instance :- PoisonPill]
  (.putFirst pool instance)
  (throw (IllegalStateException.
           "Unable to borrow JRuby instance from pool"
           (:err instance))))

(declare borrow-from-pool!*)

(schema/defn borrowed-jruby-instance :- jruby-schemas/JRubyPuppetBorrowResult
  [borrow-fn :- (schema/pred ifn?)
   pool :- jruby-schemas/pool-queue-type
   pool-context :- jruby-schemas/PoolContext
   flush-instance-fn :- (schema/pred ifn?)
   instance :- JRubyPuppetInstance]
  (let [new-state (swap! (:state instance)
                         update-in [:request-count] inc)
        max-requests (get-in pool-context [:config :max-requests-per-instance])]
    (if (and (pos? max-requests)
             (> (:request-count new-state) max-requests))
      (do
        (log/infof (str "Flushing JRuby instance %s because it has exceeded the "
                        "maximum number of requests (%s)")
                   (:id instance)
                   max-requests)
        (flush-instance-fn pool pool-context instance)
        (borrow-from-pool!* borrow-fn pool pool-context flush-instance-fn))
      instance)))

(schema/defn borrow-from-pool!* :- (schema/maybe jruby-schemas/JRubyPuppetInstanceOrRetry)
  "Given a borrow function and a pool, attempts to borrow a JRuby instance from a pool.
  If successful, updates the state information and returns the JRuby instance.
  Returns nil if the borrow function returns nil; throws an exception if
  the borrow function's return value indicates an error condition."
  [borrow-fn :- (schema/pred ifn?)
   ;; it looks unusual that we accept both the pool and the pool-context
   ;; as arguments, since the PoolContext contains a reference to a pool.  However,
   ;; in cases such as a full pool flush operation, there may be two distinct
   ;; pools in play, and the one in the pool-context may be different from the
   ;; one we're borrowing from.
   pool :- jruby-schemas/pool-queue-type
   pool-context :- jruby-schemas/PoolContext
   flush-instance-fn :- (schema/pred ifn?)]
  (let [instance (borrow-fn pool)]
    (cond (instance? PoisonPill instance)
          (borrowed-poison-pill pool instance)

          (jruby-schemas/jruby-puppet-instance? instance)
          (borrowed-jruby-instance borrow-fn pool pool-context flush-instance-fn instance)

          ((some-fn nil? jruby-schemas/retry-poison-pill?) instance)
          instance

          :else
          (throw (IllegalStateException.
                   (str "Borrowed unrecognized object from pool!: " instance))))))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyPuppetInstanceOrRetry
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext
   flush-instance-fn :- (schema/pred ifn?)]
  (borrow-from-pool!* borrow-without-timeout-fn
                      (get-pool pool-context)
                      pool-context
                      flush-instance-fn))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- (schema/maybe jruby-schemas/JRubyPuppetInstanceOrRetry)
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int
   flush-instance-fn :- (schema/pred ifn?)]
  {:pre  [(>= timeout 0)]}
  (borrow-from-pool!* (partial borrow-with-timeout-fn timeout)
                      (get-pool pool-context)
                      pool-context
                      flush-instance-fn))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyPuppetInstanceOrRetry]
  (.putFirst (:pool instance) instance))
