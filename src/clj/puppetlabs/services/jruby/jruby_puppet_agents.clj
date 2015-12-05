(ns puppetlabs.services.jruby.jruby-puppet-agents
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas])
  (:import (clojure.lang IFn)
           (com.puppetlabs.puppetserver PuppetProfiler)
           (puppetlabs.services.jruby.jruby_puppet_schemas PoisonPill RetryPoisonPill JRubyPuppetInstance)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn ^:always-validate
  next-instance-id :- schema/Int
  [id :- schema/Int
   pool-context :- jruby-schemas/PoolContext]
  (let [pool-size (jruby-internal/get-pool-size pool-context)
        next-id (+ id pool-size)]
    (if (> next-id Integer/MAX_VALUE)
      (mod next-id pool-size)
      next-id)))

(schema/defn ^:always-validate
  send-agent :- jruby-schemas/JRubyPoolAgent
  "Utility function; given a JRubyPoolAgent, send the specified function.
  Ensures that the function call is wrapped in a `shutdown-on-error`."
  [jruby-agent :- jruby-schemas/JRubyPoolAgent
   f :- IFn]
  (letfn [(agent-fn [agent-ctxt]
                    (let [shutdown-on-error (:shutdown-on-error agent-ctxt)]
                      (shutdown-on-error f))
                    agent-ctxt)]
    (send jruby-agent agent-fn)))

(declare send-flush-instance!)

(schema/defn ^:always-validate
  prime-pool!
  "Sequentially fill the pool with new JRubyPuppet instances.  NOTE: this
  function should never be called except by the pool-agent."
  [{:keys [pool-state] :as pool-context} :- jruby-schemas/PoolContext
   config :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (let [pool (:pool @pool-state)]
    (log/debug (str "Initializing JRubyPuppet instances with the following settings:\n"
                    (ks/pprint-to-string config)))
    (try
      (let [count (.remainingCapacity pool)]
        (dotimes [i count]
          (let [id (inc i)]
            (log/debugf "Priming JRubyPuppet instance %d of %d" id count)
            (jruby-internal/create-pool-instance! pool id config
                                                  (partial send-flush-instance! pool-context)
                                                  profiler)
            (log/infof "Finished creating JRubyPuppet instance %d of %d"
                       id count))))
      (catch Exception e
        (.clear pool)
        (.insertPill pool (PoisonPill. e))
        (throw (IllegalStateException. "There was a problem adding a JRubyPuppet instance to the pool." e))))))

(schema/defn ^:always-validate
  flush-instance!
  "Flush a single JRuby instance.  Create a new replacement instance
  and insert it into the specified pool."
  [pool-context :- jruby-schemas/PoolContext
   {:keys [scripting-container jruby-puppet id pool] :as instance} :- JRubyPuppetInstance
   new-pool :- jruby-schemas/pool-queue-type
   new-id   :- schema/Int
   config   :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (.unregister pool instance)
  (.terminate jruby-puppet)
  (.terminate scripting-container)
  (log/infof "Cleaned up old JRuby instance with id %s, creating replacement."
             id)
  (jruby-internal/create-pool-instance! new-pool new-id config
                                        (partial send-flush-instance! pool-context)
                                        profiler))

(schema/defn ^:always-validate
  flush-pool!
  "Flush of the current JRuby pool.  NOTE: this function should never
  be called except by the pool-agent."
  [pool-context :- jruby-schemas/PoolContext]
  ;; Since this function is only called by the pool-agent, and since this
  ;; is the only entry point into the pool flushing code that is exposed by the
  ;; service API, we know that if we receive multiple flush requests before the
  ;; first one finishes, they will be queued up and the body of this function
  ;; will be executed atomically; we don't need to worry about race conditions
  ;; between the steps we perform here in the body.
  (log/info "Flush request received; creating new JRuby pool.")
  (let [{:keys [config profiler pool-state]} pool-context
        new-pool-state (jruby-internal/create-pool-from-config config)
        new-pool (:pool new-pool-state)
        old-pool-state @pool-state
        old-pool (:pool old-pool-state)
        count    (:size old-pool-state)]
    (log/info "Replacing old JRuby pool with new instance.")
    (reset! pool-state new-pool-state)
    (log/info "Swapped JRuby pools, beginning cleanup of old pool.")
    (doseq [i (range count)]
      (try
        (let [id        (inc i)
              instance  (jruby-internal/borrow-from-pool!*
                          jruby-internal/borrow-without-timeout-fn
                          old-pool)]
          (try
            (flush-instance! pool-context instance new-pool id config profiler)
            (finally
              (.releaseItem old-pool instance false)))
          (log/infof "Finished creating JRubyPuppet instance %d of %d"
                     id count))
        (catch Exception e
          (.clear new-pool)
          (.insertPill new-pool (PoisonPill. e))
          (throw (IllegalStateException.
                   "There was a problem adding a JRubyPuppet instance to the pool."
                   e)))))
    ;; Add a "RetryPoisonPill" to the pool in case something else is in the
    ;; process of borrowing from the old pool.
    (.insertPill old-pool (RetryPoisonPill. old-pool))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  pool-agent :- jruby-schemas/JRubyPoolAgent
  "Given a shutdown-on-error function, create an agent suitable for use in managing
  JRuby pools."
  [shutdown-on-error-fn :- (schema/pred ifn?)]
  (agent {:shutdown-on-error shutdown-on-error-fn}))

(schema/defn ^:always-validate
  send-prime-pool! :- jruby-schemas/JRubyPoolAgent
  "Sends a request to the agent to prime the pool using the given pool context."
  [pool-context :- jruby-schemas/PoolContext]
  (let [{:keys [pool-agent config profiler]} pool-context]
    (send-agent pool-agent #(prime-pool! pool-context config profiler))))

(schema/defn ^:always-validate
  send-flush-pool! :- jruby-schemas/JRubyPoolAgent
  "Sends requests to the agent to flush the existing pool and create a new one."
  [pool-context :- jruby-schemas/PoolContext]
  (send-agent (:pool-agent pool-context) #(flush-pool! pool-context)))

(schema/defn ^:always-validate
  send-flush-instance! :- jruby-schemas/JRubyPoolAgent
  "Sends requests to the flush-instance agent to flush the instance and create a new one."
  [pool-context :- jruby-schemas/PoolContext
   pool :- jruby-schemas/pool-queue-type
   instance :- JRubyPuppetInstance]
  (let [{:keys [flush-instance-agent config profiler]} pool-context
        id (next-instance-id (:id instance) pool-context)]
    (send-agent flush-instance-agent #(flush-instance! pool-context instance pool id config profiler))))
