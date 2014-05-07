(ns puppetlabs.master.services.jruby.jruby-puppet-core
  (:import (java.util.concurrent ArrayBlockingQueue BlockingQueue TimeUnit)
           (com.puppetlabs.master JRubyPuppet)
           (java.util HashMap)
           (org.jruby RubyInstanceConfig$CompileMode CompatVersion)
           (org.jruby.embed ScriptingContainer LocalContextScope)
           (clojure.lang Atom))
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [schema.core :as schema]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def illegal-env-names
  "These environment names are not allowed by Puppet."
  #{"main" "master" "agent" "user"})

(def default-environment
  "The production environment is the default environment, and it is required
  in the config."
  "production")

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
  "jvm-puppet-lib")

(defrecord PoisonPill
  ;; A sentinel object to put into a pool in case an error occurs while we're trying
  ;; to populate it.  This can be used by the `borrow` functions to detect error
  ;; state in a thread-safe manner.
  [err])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def PoolDefinition
  {:environment schema/Str
   :size schema/Int})

(def PoolConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :load-path  - a vector of file paths, containing the locations of puppet source code.

    * :jruby-pools - a list of JRubyPuppet pool descriptions


    * :master-conf-dir - file path to puppetmaster's conf dir;
                         if not specified, will use the puppet default.

    * :master-var-dir - path to the puppetmaster' var dir;
                        if not specified, will use the puppet default."
  {:load-path [schema/Str]
   (schema/optional-key :master-conf-dir) schema/Str
   (schema/optional-key :master-var-dir) schema/Str
   :jruby-pools [PoolDefinition]})

(def PoolData
  "A map that describes all attributes of a particular JRubyPuppet pool."
  {:environment   schema/Keyword
   :pool          pool-queue-type
   :size          schema/Int
   :initialized?  schema/Bool})

(def PoolsMap
  "A map containing all of the JRubyPuppet pool instances."
  {schema/Keyword PoolData})

(def PoolsState
  "An atom containing the current state of all of the JRubyPuppet pools."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check PoolsMap @%)))
               'PoolsState))

(def PoolContext
  "The data structure that stores all JRubyPuppet pools and the original configuration."
  {:config PoolConfig
   :pools  PoolsState})

(def PoolDescriptor
  "A map which is used to describe a JRubyPuppet pool."
  {:environment schema/Keyword})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn create-scripting-container
  "Creates an instance of `org.jruby.embed.ScriptingContainer` and loads up the
  puppet and facter code inside it."
  [load-path]
  {:pre [(sequential? load-path)
         (every? string? load-path)]
   :post [(instance? ScriptingContainer %)]}
  ;; for information on other legal values for `LocalContextScope`, there
  ;; is some documentation available in the JRuby source code; e.g.:
  ;; https://github.com/jruby/jruby/blob/1.7.11/core/src/main/java/org/jruby/embed/LocalContextScope.java#L58
  ;; I'm convinced that this is the safest and most reasonable value
  ;; to use here, but we could potentially explore optimizations in the future.
  (doto (ScriptingContainer. LocalContextScope/SINGLETHREAD)
    (.setLoadPaths (cons ruby-code-dir
                         (map fs/absolute-path load-path)))
    (.setCompatVersion (CompatVersion/RUBY1_9))
    (.setCompileMode RubyInstanceConfig$CompileMode/OFF)
    (.setEnvironment jruby-puppet-env)
    (.runScriptlet "require 'puppet/jvm/master'")))

(defn create-jruby-instance
  "Creates a new JRubyPuppet instance.  See the docs on `create-jruby-pool`
  for the contents of `config`."
  [config]
  {:pre [((some-fn nil? vector?) (:load-path config))]
   :post [(instance? JRubyPuppet %)]}
  (let [{:keys [load-path master-conf-dir master-var-dir]} config]
    (when-not load-path
      (throw (Exception.
               "JRuby service missing config value 'load-path'")))
    (let [scripting-container (create-scripting-container load-path)
          ruby-puppet-class   (.runScriptlet scripting-container "Puppet::Jvm::Master")
          jruby-config        (HashMap.)]
      (when master-conf-dir
        (.put jruby-config "confdir" (fs/absolute-path master-conf-dir)))
      (when master-var-dir
        (.put jruby-config "vardir" (fs/absolute-path master-var-dir)))

      (.callMethod scripting-container ruby-puppet-class "new" jruby-config JRubyPuppet))))

(schema/defn ^:always-validate
  get-pool-data-by-descriptor :- (schema/maybe PoolData)
  "Returns the JRubyPuppet pool description which matches the pool descriptor. Currently
  only the :environment attribute is used to obtain a match. If no match is found
  then nil is returned."
  [context :- PoolContext
   descriptor :- PoolDescriptor]
  (get @(:pools context) (:environment descriptor)))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRubyPuppet's."
  [size]
  {:post [(instance? pool-queue-type %)]}
  (ArrayBlockingQueue. size))

(schema/defn ^:always-validate
  pools-matching-environment :- schema/Int
  "The number of pools in the config that are marked with an environment."
  [config :- PoolConfig
   env    :- schema/Str]
  (count (filter #(= (:environment %) env) (:jruby-pools config))))

(schema/defn ^:always-validate
  ensure-no-duplicate-pools!
  "Check if there are any pools which contain the same description."
  [config :- PoolConfig]
  (doseq [pool (:jruby-pools config)]
    (when (> (pools-matching-environment config (:environment pool)) 1)
      (throw (IllegalArgumentException. "Two or more JRuby pools were found with same environment.")))))

(schema/defn ^:always-validate
  validate-config!
  "Is the JRubyPuppetPool config map valid? Aside from checking schema validity,
  this function also checks to make sure there are no illegal environment names
  present and the required default environment is defined. If any problem occurs
  an exception is thrown, otherwise true is returned."
  [config :- PoolConfig]
  (if-not (some #(= (:environment %) default-environment)
                (:jruby-pools config))
          (throw (IllegalArgumentException. (str "The " default-environment
                                                 " environment must be defined in "
                                                 "one of the configured jruby pools."))))
  (if-let [illegal-env (some #(illegal-env-names (:environment %))
                             (:jruby-pools config))]
    (throw (IllegalArgumentException. (str "The specified environment name '"
                                           illegal-env "' in the jruby pool "
                                           "config it not valid. "))))
  (ensure-no-duplicate-pools! config))

(schema/defn ^:always-validate
  create-pool-from-config :- PoolData
  "Create a new PoolData based on the config input."
  [{:keys [environment size]} :- PoolDefinition]
  {:environment     (keyword environment)
   :pool            (instantiate-free-pool size)
   :size            size
   :initialized?    false})

(schema/defn ^:always-validate
  add-pool-from-config :- PoolsMap
  "Add a pool to the pools map based on a PoolDefinition"
  [pools-map :- PoolsMap
   pool-def :- PoolDefinition]
  (assoc pools-map (keyword (:environment pool-def)) (create-pool-from-config pool-def)))

(defn validate-instance-from-pool!
  "Validate an instance.  The main purpose of this function is to check for
  a poison pill, which indicates that there was an error when initializing the
  pool.  If the poison pill is found, returns it to the pool (so that it will
  be available to other callers) and throws the poison pill's exception.  Otherwise
  returns the instance that was passed in."
  [instance pool]
  {:post [((some-fn nil? #(instance? JRubyPuppet %)) %)]}
  (when (instance? PoisonPill instance)
    (.put pool instance)
    (throw (IllegalStateException. "Unable to borrow JRuby instance from pool" (:err instance))))
  instance)

(schema/defn ^:always-validate
  mark-as-initialized! :- PoolData
  "Updates pool data to reflect that pool initialization has completed successfully."
  [pool :- PoolData]
  (assoc pool :initialized? true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-pool-context :- PoolContext
  "Creates a new JRubyPuppet pool context with empty pools. Once the JRubyPuppet
  pool object has been created, it will need to have its pools filled using
  `prime-pools!`."
  [config]
  (validate-config! config)
  {:config config
   :pools  (atom (reduce add-pool-from-config
                    {}
                    (:jruby-pools config)))})

(schema/defn ^:always-validate
  prime-pools!
  "Sequentially fill each pool with new JRubyPuppet instances."
  [context :- PoolContext]
  (let [config (:config context)]
    (log/debugf (str "Initializing JRubyPuppet instances with the following settings:"
                     "\tload path: %s\n"
                     "\tmaster conf dir: %s")
                (:load-path config)
                (:master-conf-dir config))
    (doseq [{:keys [environment pool]} (vals @(:pools context))]
      (try
        (let [count (.remainingCapacity pool)]
          (dotimes [i count]
            (log/debugf (str "Priming JRubyPuppet for the " (name environment)
                             " environment instance %d of %s") (inc i) count)
            (.put pool (create-jruby-instance config))
            (log/info "Finished creation the JRubyPuppet instance for the"
                      (name environment) "environment" (inc i) "of" count))
          (swap! (:pools context) update-in [environment] mark-as-initialized!))
        (catch Exception e
          (.clear pool)
          (.put pool (PoisonPill. e))
          (throw (IllegalStateException. "There was a problem adding a JRubyPuppet instance to the pool." e)))))))

(schema/defn ^:always-validate
  free-instance-count
  "Returns the number of JRubyPuppet instances available in the pool."
  [context :- PoolContext
   descriptor :- PoolDescriptor]
  {:post [(>= % 0)]}
  (.size (:pool (get-pool-data-by-descriptor context descriptor))))

(schema/defn ^:always-validate
  borrow-from-pool
  "Borrows a JRubyPuppet interpreter from the pool which matches the provided
  pool-descriptor. If there are no instances left in the pool then this function
  will block until there is one available."
  [context :- PoolContext
   descriptor :- PoolDescriptor]
  {:post [(instance? JRubyPuppet %)]}
  (let [{:keys [pool]}   (get-pool-data-by-descriptor context descriptor)
        instance  (.take pool)]
    (validate-instance-from-pool! instance pool)))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- (schema/maybe JRubyPuppet)
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [context :- PoolContext
   descriptor :- PoolDescriptor
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (let [{:keys [pool]}    (get-pool-data-by-descriptor context descriptor)
        instance          (.poll pool timeout TimeUnit/MILLISECONDS)]
    (validate-instance-from-pool! instance pool)))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed JRubyPuppet instance to its free pool."
  [context :- PoolContext
   descriptor :- PoolDescriptor
   instance :- JRubyPuppet]
  (let [pool-data (get-pool-data-by-descriptor context descriptor)]
    (if-not pool-data
      (throw (IllegalStateException.
               (str "No pool was found that could be described by "
                    descriptor))))
    (.put (:pool pool-data) instance)))
