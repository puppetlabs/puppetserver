(ns puppetlabs.services.jruby.jruby-puppet-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.services.jruby.jruby-puppet-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-borrow-timeout
  "Default timeout when borrowing instances from the JRuby pool in
   milliseconds. Current value is 1200000ms, or 20 minutes."
  1200000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; This service uses TK's normal config service instead of the
;; PuppetServerConfigService.  This is because that service depends on this one.

(trapperkeeper/defservice jruby-puppet-pooled-service
                          jruby/JRubyPuppetService
                          [[:ConfigService get-in-config]
                           [:ShutdownService shutdown-on-error]
                           [:PuppetProfilerService get-profiler]]
  (init
    [this context]
    (let [config            (-> (get-in-config [:jruby-puppet])
                                (assoc :http-client-ssl-protocols
                                       (get-in-config [:http-client :ssl-protocols]))
                                (assoc :http-client-cipher-suites
                                       (get-in-config [:http-client :cipher-suites])))
          service-id        (tk-services/service-id this)
          agent-shutdown-fn (partial shutdown-on-error service-id)
          pool-agent        (jruby-agents/pool-agent agent-shutdown-fn)
          profiler          (get-profiler)
          borrow-timeout    (get-in-config [:jruby-puppet :borrow-timeout] default-borrow-timeout)]
      (core/verify-config-found! config)
      (log/info "Initializing the JRuby service")
      (let [pool-context (core/create-pool-context config profiler)]
        (jruby-agents/send-prime-pool! pool-context pool-agent)
        (-> context
            (assoc :pool-context pool-context)
            (assoc :pool-agent pool-agent)
            (assoc :borrow-timeout borrow-timeout)))))

  (borrow-instance
    [this]
    (let [pool-context   (:pool-context (tk-services/service-context this))
          pool           (core/get-pool pool-context)
          borrow-timeout (:borrow-timeout (tk-services/service-context this))]
      (core/borrow-from-pool-with-timeout pool borrow-timeout)))

  (return-instance
    [this jruby-instance]
    (core/return-to-pool jruby-instance))

  (free-instance-count
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))
          pool         (core/get-pool pool-context)]
      (core/free-instance-count pool)))

  (mark-all-environments-expired!
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))]
      (core/mark-all-environments-expired! pool-context)))

  (flush-jruby-pool!
    [this]
    (let [service-context (tk-services/service-context this)
          {:keys [pool-context pool-agent]} service-context]
      (jruby-agents/send-flush-pool! pool-context pool-agent))))

(defmacro with-jruby-puppet
  "Encapsulates the behavior of borrowing and returning an instance of
  JRubyPuppet.  Example usage:

  (let [jruby-service (get-service :JRubyPuppetService)]
    (with-jruby-puppet
      jruby-puppet
      jruby-service
      (do-something-with-a-jruby-puppet-instance jruby-puppet)))

  Will throw an IllegalStateException if borrowing an instance of
  JRubyPuppet times out."
  [jruby-puppet jruby-service & body]
  `(loop [pool-instance# (jruby/borrow-instance ~jruby-service)]
     (if (nil? pool-instance#)
       (throw (IllegalStateException.
                "Error: Attempt to borrow a JRuby instance from the pool timed out")))
     (if (core/retry-poison-pill? pool-instance#)
       (do
         (jruby-core/return-to-pool pool-instance#)
         (recur (jruby/borrow-instance ~jruby-service)))
       (let [~jruby-puppet (:jruby-puppet pool-instance#)]
         (try
           ~@body
           (finally
             (jruby/return-instance ~jruby-service pool-instance#)))))))
