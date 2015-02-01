(ns puppetlabs.services.jruby.jruby-puppet-core
  (:require [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents])
  (:import (puppetlabs.services.jruby.jruby_puppet_schemas JRubyPuppetInstance)
           (com.puppetlabs.puppetserver PuppetProfiler)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def default-pool-size
  "The default size of each JRuby pool."
  (+ 2 (ks/num-cpus)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

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
  pool->vec :- [JRubyPuppetInstance]
  [context :- jruby-schemas/PoolContext]
  (-> (get-pool context)
      .iterator
      iterator-seq
      vec))

(defn verify-config-found!
  [config]
  (if (or (not (map? config))
          (empty? config))
    (throw (IllegalArgumentException. (str "No configuration data found.  Perhaps "
                                           "you did not specify the --config option?")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  initialize-config :- jruby-schemas/JRubyPuppetConfig
  [config :- {schema/Keyword schema/Any}]
  (-> (get-in config [:jruby-puppet])
      (assoc :ruby-load-path (get-in config [:os-settings :ruby-load-path]))
      (assoc :http-client-ssl-protocols
             (get-in config [:http-client :ssl-protocols]))
      (assoc :http-client-cipher-suites
             (get-in config [:http-client :cipher-suites]))
      (update-in [:master-conf-dir] #(or % nil))
      (update-in [:master-var-dir] #(or % nil))
      (update-in [:max-active-instances] #(or % default-pool-size))
      (update-in [:max-requests-per-instance] #(or % 0))))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRubyPuppet pool context with an empty pool. Once the JRubyPuppet
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)
   agent-shutdown-fn :- (schema/maybe (schema/pred ifn?))]
  {:config     config
   :profiler   profiler
   :pool-agent (jruby-agents/pool-agent agent-shutdown-fn)
   :pool-state (atom (jruby-internal/create-pool-from-config config))})

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
  mark-all-environments-expired!
  [context :- jruby-schemas/PoolContext]
  (doseq [jruby-instance (pool->vec context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyPuppetInstanceOrRetry
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext]
  (jruby-internal/borrow-from-pool pool-context))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- (schema/maybe jruby-schemas/JRubyPuppetInstanceOrRetry)
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (jruby-internal/borrow-from-pool-with-timeout pool-context timeout))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyPuppetInstanceOrRetry]
  (jruby-internal/return-to-pool instance))
