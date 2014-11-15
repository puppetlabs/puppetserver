(ns puppetlabs.services.jruby.jruby-puppet-agents
  (:import (clojure.lang IFn Agent)
           (com.puppetlabs.puppetserver PuppetProfiler))
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))


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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  jruby-pool-agent :- JRubyPoolAgent
  "Given a shutdown-on-error function, create an agent suitable for use in managing
  JRuby pools."
  [shutdown-on-error-fn :- IFn]
  (agent {:shutdown-on-error shutdown-on-error-fn}))

(schema/defn ^:always-validate
  send-prime-pool! :- JRubyPoolAgent
  "Sends a request to the agent to prime the pool using the given pool context."
  [prime-pool-agent :- JRubyPoolAgent
   pool-state :- jruby-core/PoolStateContainer
   config :- jruby-core/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (send-agent prime-pool-agent #(jruby-core/prime-pool! pool-state config profiler)))
