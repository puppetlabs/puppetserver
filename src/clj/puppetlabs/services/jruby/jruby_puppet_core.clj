(ns puppetlabs.services.jruby.jruby-puppet-core
  (:import (java.util.concurrent ArrayBlockingQueue BlockingQueue TimeUnit)
           (java.util HashMap)
           (org.jruby RubyInstanceConfig$CompileMode CompatVersion)
           (org.jruby.embed ScriptingContainer LocalContextScope)
           (clojure.lang Atom)
           (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet
                                        EnvironmentRegistry))
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def default-pool-size
  "The default size of each JRuby pool."
  (+ 2 (ks/num-cpus)))

(def pool-queue-type
  "The Java datastructure type used to store JRubyPuppet instances which are
  free to be borrowed."
  BlockingQueue)

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

(defrecord PoisonPill
  ;; A sentinel object to put into a pool in case an error occurs while we're trying
  ;; to populate it.  This can be used by the `borrow` functions to detect error
  ;; state in a thread-safe manner.
  [err])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def JRubyPuppetConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :ruby-load-path - a vector of file paths, containing the locations of puppet source code.

    * :gem-home - The location that JRuby gems are stored

    * :master-conf-dir - file path to puppetmaster's conf dir;
        if not specified, will use the puppet default.

    * :master-var-dir - path to the puppetmaster' var dir;
        if not specified, will use the puppet default.

    * :max-active-instances - The maximum number of JRubyPuppet instances that
        will be pooled. If not specified, the system's
        number of CPUs+2 will be used.

    * :http-client-ssl-protocols - A list of legal SSL protocols that may be used
        when https client requests are made.

    * :http-client-cipher-suites - A list of legal SSL cipher suites that may
        be used when https client requests are made."
  {:ruby-load-path                                  [schema/Str]
   :gem-home                                        schema/Str
   (schema/optional-key :master-conf-dir)           schema/Str
   (schema/optional-key :master-var-dir)            schema/Str
   (schema/optional-key :max-active-instances)      schema/Int
   (schema/optional-key :http-client-ssl-protocols) [schema/Str]
   (schema/optional-key :http-client-cipher-suites) [schema/Str]})

(def PoolState
  "A map that describes all attributes of a particular JRubyPuppet pool."
  {:pool         pool-queue-type
   :size         schema/Int
   :initialized? schema/Bool})

(def PoolStateContainer
  "An atom containing the current state of all of the JRubyPuppet pool."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check PoolState @%)))
               'PoolStateContainer))

(def PoolContext
  "The data structure that stores all JRubyPuppet pools and the original configuration."
  {:config     JRubyPuppetConfig
   :profiler   (schema/maybe PuppetProfiler)
   :pool-state PoolStateContainer})

(def JRubyPuppetInstance
  "A map with objects pertaining to an individual entry in the JRubyPuppet pool."
  {:id                    schema/Int
   :jruby-puppet          JRubyPuppet
   :scripting-container   ScriptingContainer
   :environment-registry  (schema/both
                            EnvironmentRegistry
                            (schema/pred
                              #(satisfies? puppet-env/EnvironmentStateContainer %)))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn prep-scripting-container
  [scripting-container ruby-load-path gem-home]
  (doto scripting-container
    (.setLoadPaths (cons ruby-code-dir
                         (map fs/absolute-path ruby-load-path)))
    (.setCompatVersion (CompatVersion/RUBY1_9))
    (.setCompileMode RubyInstanceConfig$CompileMode/OFF)
    (.setEnvironment (merge {"GEM_HOME" gem-home} jruby-puppet-env))))

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
  create-pool-instance :- JRubyPuppetInstance
  "Creates a new pool instance."
  [id       :- schema/Int
   config   :- JRubyPuppetConfig
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

      {:id                    id
       :jruby-puppet          (.callMethod scripting-container
                                           ruby-puppet-class
                                           "new"
                                           (into-array Object
                                                       [puppet-config puppet-server-config])
                                           JRubyPuppet)
       :scripting-container   scripting-container
       :environment-registry  env-registry})))

(schema/defn ^:always-validate
  get-pool-state :- PoolState
  "Gets the PoolState from the pool context."
  [context :- PoolContext]
  @(:pool-state context))

(schema/defn ^:always-validate
  get-pool :- pool-queue-type
  "Gets the JRubyPuppet pool object from the pool context."
  [context :- PoolContext]
  (:pool (get-pool-state context)))

(schema/defn ^:always-validate
  pool->vec :- [JRubyPuppetInstance]
  [context :- PoolContext]
  (-> (get-pool context)
      .iterator
      iterator-seq
      vec))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRubyPuppet's."
  [size]
  {:post [(instance? pool-queue-type %)]}
  (ArrayBlockingQueue. size))

(defn verify-config-found!
  [config]
  (if (or (not (map? config))
          (empty? config))
    (throw (IllegalArgumentException. (str "No configuration data found.  Perhaps "
                                           "you did not specify the --config option?")))))

(schema/defn ^:always-validate
  create-pool-from-config :- PoolStateContainer
  "Create a new PoolData based on the config input."
  [{size :max-active-instances} :- JRubyPuppetConfig]
  (let [size (or size default-pool-size)]
    (atom
      {:pool         (instantiate-free-pool size)
       :size         size
       :initialized? false})))

(defn validate-instance-from-pool!
  "Validate an instance.  The main purpose of this function is to check for
  a poison pill, which indicates that there was an error when initializing the
  pool.  If the poison pill is found, returns it to the pool (so that it will
  be available to other callers) and throws the poison pill's exception.
  Otherwise returns the instance that was passed in."
  [instance pool]
  {:post [((some-fn nil? #(nil? (schema/check JRubyPuppetInstance %))) %)]}
  (when (instance? PoisonPill instance)
    (.put pool instance)
    (throw (IllegalStateException. "Unable to borrow JRuby instance from pool"
                                   (:err instance))))
  instance)

(schema/defn ^:always-validate
  mark-as-initialized! :- PoolState
  "Updates the PoolState map to reflect that pool initialization has completed
  successfully."
  [pool-state :- PoolStateContainer]
  (swap! pool-state assoc :initialized? true))

(schema/defn ^:always-validate
  prime-pool!
  "Sequentially fill the pool with new JRubyPuppet instances."
  [pool-state :- PoolStateContainer
   config :- JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (let [pool (:pool @pool-state)]
    (log/debug (str "Initializing JRubyPuppet instances with the following settings:\n"
                    (ks/pprint-to-string config)))
    (try
      (let [count (.remainingCapacity pool)]
        (dotimes [i count]
          (let [id (inc i)]
            (log/debugf "Priming JRubyPuppet instance %d of %d" id count)
            (.put pool (create-pool-instance id config profiler))
            (log/infof "Finished creating JRubyPuppet instance %d of %d"
                       id count))
          (mark-as-initialized! pool-state)))
      (catch Exception e
        (.clear pool)
        (.put pool (PoisonPill. e))
        (throw (IllegalStateException. "There was a problem adding a JRubyPuppet instance to the pool." e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-pool-context :- PoolContext
  "Creates a new JRubyPuppet pool context with an empty pool. Once the JRubyPuppet
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config profiler]
  {:config     config
   :profiler   profiler
   :pool-state (create-pool-from-config config)})

(schema/defn ^:always-validate
  free-instance-count
  "Returns the number of JRubyPuppet instances available in the pool."
  [pool :- pool-queue-type]
  {:post [(>= % 0)]}
  (.size pool))

(schema/defn ^:always-validate
  mark-all-environments-expired!
  [context :- PoolContext]
  (doseq [jruby-instance (pool->vec context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))

(schema/defn ^:always-validate
  borrow-from-pool :- JRubyPuppetInstance
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool :- pool-queue-type]
  (let [instance (.take pool)]
    (validate-instance-from-pool! instance pool)))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- (schema/maybe JRubyPuppetInstance)
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool :- pool-queue-type
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (let [instance (.poll pool timeout TimeUnit/MILLISECONDS)]
    (validate-instance-from-pool! instance pool)))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [pool :- pool-queue-type
   instance :- JRubyPuppetInstance]
  (.put pool instance))
