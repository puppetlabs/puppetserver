(ns puppetlabs.services.jruby.jruby-puppet-agents
  (:import (clojure.lang IFn Agent)
           (puppetlabs.services.jruby.jruby_puppet_core PoisonPill RetryPoisonPill)
           (com.puppetlabs.puppetserver PuppetProfiler))
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def JRubyPoolAgent
  "An agent configured for use in managing JRuby pools"
  (schema/both Agent
               (schema/pred
                 (fn [a]
                   (let [state @a]
                     (and
                       (map? state)
                       (ifn? (:shutdown-on-error state))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn ^:always-validate
  send-agent :- JRubyPoolAgent
  "Utility function; given a JRubyPoolAgent, send the specified function.
  Ensures that the function call is wrapped in a `shutdown-on-error`."
  [jruby-agent :- JRubyPoolAgent
   f :- IFn]
  (letfn [(agent-fn [agent-ctxt]
                    (let [shutdown-on-error (:shutdown-on-error agent-ctxt)]
                      (shutdown-on-error f))
                    agent-ctxt)]
    (send jruby-agent agent-fn)))

(schema/defn ^:always-validate
  prime-pool!
  "Sequentially fill the pool with new JRubyPuppet instances.  NOTE: this
  function should never be called except by the pool-agent."
  [pool-state :- jruby-core/PoolStateContainer
   config :- jruby-core/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (let [pool (:pool @pool-state)]
    (log/debug (str "Initializing JRubyPuppet instances with the following settings:\n"
                    (ks/pprint-to-string config)))
    (try
      (let [count (.remainingCapacity pool)]
        (dotimes [i count]
          (let [id (inc i)]
            (log/debugf "Priming JRubyPuppet instance %d of %d" id count)
            (jruby-core/create-pool-instance! pool id config profiler)
            (log/infof "Finished creating JRubyPuppet instance %d of %d"
                       id count))))
      (catch Exception e
        (.clear pool)
        (.putFirst pool (PoisonPill. e))
        (throw (IllegalStateException. "There was a problem adding a JRubyPuppet instance to the pool." e))))))

(schema/defn ^:always-validate
  flush-instance!
  "Flush a single JRuby instance.  Create a new replacement instance
  and insert it into the specified pool."
  [{:keys [scripting-container id]} :- jruby-core/JRubyPuppetInstanceOrRetry
   new-pool :- jruby-core/pool-queue-type
   new-id   :- schema/Int
   config   :- jruby-core/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (.terminate scripting-container)
  (log/infof "Cleaned up old JRuby instance with id %s, creating replacement."
             id)
  (jruby-core/create-pool-instance! new-pool new-id config profiler))

(schema/defn ^:always-validate
  flush-pool!
  "Flush of the current JRuby pool.  NOTE: this function should never
  be called except by the pool-agent."
  [pool-context :- jruby-core/PoolContext]
  ;; Since this function is only called by the pool-agent, and since this
  ;; is the only entry point into the pool flushing code that is exposed by the
  ;; service API, we know that if we receive multiple flush requests before the
  ;; first one finishes, they will be queued up and the body of this function
  ;; will be executed atomically; we don't need to worry about race conditions
  ;; between the steps we perform here in the body.
  (log/info "Flush request received; creating new JRuby pool.")
  (let [{:keys [config profiler pool-state]} pool-context
        new-pool-state (jruby-core/create-pool-from-config config)
        new-pool (:pool new-pool-state)
        old-pool @pool-state
        count    (:size old-pool)]
    (log/info "Replacing old JRuby pool with new instance.")
    (reset! pool-state new-pool-state)
    (log/info "Swapped JRuby pools, beginning cleanup of old pool.")
    (doseq [i (range count)]
      (try
        (let [id        (inc i)
              instance  (jruby-core/borrow-from-pool (:pool old-pool))]
          (flush-instance! instance new-pool id config profiler)
          (log/infof "Finished creating JRubyPuppet instance %d of %d"
                     id count))
        (catch Exception e
          (.clear new-pool)
          (.putFirst new-pool (PoisonPill. e))
          (throw (IllegalStateException.
                   "There was a problem adding a JRubyPuppet instance to the pool."
                   e)))))
    (jruby-core/return-to-pool (RetryPoisonPill. (:pool old-pool)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  pool-agent :- JRubyPoolAgent
  "Given a shutdown-on-error function, create an agent suitable for use in managing
  JRuby pools."
  [shutdown-on-error-fn :- IFn]
  (agent {:shutdown-on-error shutdown-on-error-fn}))

(schema/defn ^:always-validate
  send-prime-pool! :- JRubyPoolAgent
  "Sends a request to the agent to prime the pool using the given pool context."
  [pool-context :- jruby-core/PoolContext
   pool-agent :- JRubyPoolAgent]
  (let [{:keys [pool-state config profiler]} pool-context]
    (send-agent pool-agent #(prime-pool! pool-state config profiler))))

(schema/defn ^:always-validate
  send-flush-pool! :- JRubyPoolAgent
  "Sends requests to the agent to flush the existing pool and create a new one."
  [pool-context :- jruby-core/PoolContext
   pool-agent :- JRubyPoolAgent]
  (send-agent pool-agent #(flush-pool! pool-context)))
