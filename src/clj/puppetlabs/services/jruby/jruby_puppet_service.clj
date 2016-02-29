(ns puppetlabs.services.jruby.jruby-puppet-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.services.jruby.jruby-puppet-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby]
            [slingshot.slingshot :as sling]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; This service uses TK's normal config service instead of the
;; PuppetServerConfigService.  This is because that service depends on this one.

(trapperkeeper/defservice jruby-puppet-pooled-service
                          jruby/JRubyPuppetService
                          [[:ConfigService get-config]
                           [:ShutdownService shutdown-on-error]
                           [:PuppetProfilerService get-profiler]]
  (init
    [this context]
    (let [config            (core/initialize-config (get-config))
          service-id        (tk-services/service-id this)
          agent-shutdown-fn (partial shutdown-on-error service-id)
          profiler          (get-profiler)]
      (core/verify-config-found! config)
      (log/info "Initializing the JRuby service")
      (if (:use-legacy-auth-conf config)
        (log/warn "The 'jruby-puppet.use-legacy-auth-conf' setting is set to"
                  "'true'.  Support for the legacy Puppet auth.conf file is"
                  "deprecated and will be removed in a future release.  Change"
                  "this setting to 'false' and migrate your authorization rule"
                  "definitions in the /etc/puppetlabs/puppet/auth.conf file to"
                  "the /etc/puppetlabs/puppetserver/conf.d/auth.conf file."))
      (core/add-facter-jar-to-system-classloader (:ruby-load-path config))
      (let [pool-context (core/create-pool-context config profiler agent-shutdown-fn)]
        (jruby-agents/send-prime-pool! pool-context)
        (-> context
            (assoc :pool-context pool-context)
            (assoc :borrow-timeout (:borrow-timeout config))
            (assoc :event-callbacks (atom []))
            (assoc :environment-class-info-tags (atom {}))))))
  (stop
   [this context]
   (let [{:keys [pool-context]} (tk-services/service-context this)
         on-complete (promise)]
     (log/debug "Beginning flush of JRuby pools for shutdown")
     (jruby-agents/send-flush-pool-for-shutdown! pool-context on-complete)
     @on-complete
     (log/debug "Finished flush of JRuby pools for shutdown"))
   context)

  (borrow-instance
    [this reason]
    (let [{:keys [pool-context borrow-timeout event-callbacks]} (tk-services/service-context this)]
      (core/borrow-from-pool-with-timeout pool-context borrow-timeout reason @event-callbacks)))

  (return-instance
    [this jruby-instance reason]
    (let [event-callbacks (:event-callbacks (tk-services/service-context this))]
      (core/return-to-pool jruby-instance reason @event-callbacks)))

  (free-instance-count
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))
          pool         (core/get-pool pool-context)]
      (core/free-instance-count pool)))

  (mark-environment-expired!
    [this env-name]
    (let [{:keys [environment-class-info-tags pool-context]}
          (tk-services/service-context this)]
      (core/mark-environment-expired! pool-context
                                      env-name
                                      environment-class-info-tags)))

  (mark-all-environments-expired!
    [this]
    (let [{:keys [environment-class-info-tags pool-context]}
          (tk-services/service-context this)]
      (core/mark-all-environments-expired! pool-context
                                           environment-class-info-tags)))

  (get-environment-class-info
    [this jruby-instance env-name]
    (.getClassInfoForEnvironment jruby-instance env-name))

  (get-environment-class-info-tag
   [this env-name]
   (let [environment-class-info (:environment-class-info-tags
                                 (tk-services/service-context this))]
     (get-in @environment-class-info [env-name :tag])))

  (get-environment-class-info-tag-last-updated!
   [this env-name]
   (let [environment-class-info (:environment-class-info-tags
                                 (tk-services/service-context this))]
     (core/get-environment-class-info-tag-last-updated!
      environment-class-info
      env-name)))

  (set-environment-class-info-tag!
   [this env-name tag last-update-before-tag-computed]
   (let [environment-class-info (:environment-class-info-tags
                                 (tk-services/service-context this))]
     (swap! environment-class-info
            core/environment-class-info-cache-updated-with-tag
            env-name
            tag
            last-update-before-tag-computed)))

  (flush-jruby-pool!
    [this]
    (let [service-context (tk-services/service-context this)
          {:keys [pool-context]} service-context]
      (jruby-agents/send-flush-and-repopulate-pool! pool-context)))

  (register-event-handler
    [this callback-fn]
    (let [event-callbacks (:event-callbacks (tk-services/service-context this))]
      (swap! event-callbacks conj callback-fn))))

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
  [jruby-puppet jruby-service reason & body]
  `(loop [pool-instance# (jruby/borrow-instance ~jruby-service ~reason)]
     (if (nil? pool-instance#)
       (sling/throw+
         {:type    ::jruby-timeout
          :message (str "Attempt to borrow a JRuby instance from the pool "
                        "timed out; Puppet Server is temporarily overloaded. If "
                        "you get this error repeatedly, your server might be "
                        "misconfigured or trying to serve too many agent nodes. "
                        "Check Puppet Server settings: "
                        "jruby-puppet.max-active-instances.")}))
     (when (jruby-schemas/shutdown-poison-pill? pool-instance#)
       (jruby/return-instance ~jruby-service pool-instance# ~reason)
       (sling/throw+
        {:type    ::service-unavailable
         :message (str "Attempted to borrow a JRuby instance from the pool "
                       "during a shutdown. Please try again.")}))
     (if (jruby-schemas/retry-poison-pill? pool-instance#)
       (do
         (jruby/return-instance ~jruby-service pool-instance# ~reason)
         (recur (jruby/borrow-instance ~jruby-service ~reason)))
       (let [~jruby-puppet (:jruby-puppet pool-instance#)]
         (try
           ~@body
           (finally
             (jruby/return-instance ~jruby-service pool-instance# ~reason)))))))

(defmacro with-lock
  "Acquires a lock on the pool, executes the body, and releases the lock."
  [jruby-service reason & body]
  `(let [context# (-> ~jruby-service
                      tk-services/service-context)
         pool# (-> context#
                   :pool-context
                   core/get-pool)
         event-callbacks# (:event-callbacks context#)]
     (core/lock-pool pool# ~reason @event-callbacks#)
     (try
      ~@body
      (finally
        (core/unlock-pool pool# ~reason @event-callbacks#)))))
