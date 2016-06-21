(ns puppetlabs.services.jruby.jruby-puppet-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.ring-middleware.utils :as ringutils]
            [puppetlabs.services.jruby.jruby-puppet-core :as core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; This service uses TK's normal config service instead of the
;; PuppetServerConfigService.  This is because that service depends on this one.

(trapperkeeper/defservice jruby-puppet-pooled-service
                          jruby/JRubyPuppetService
                          [[:ConfigService get-config]
                           [:ShutdownService shutdown-on-error]
                           [:PuppetProfilerService get-profiler]
                           [:PoolManagerService create-pool]]
  (init
    [this context]
    (let [jruby-config (core/initialize-and-create-jruby-config
                        (get-config)
                        (get-profiler)
                        (partial shutdown-on-error (tk-services/service-id this))
                        true)
          pool-context (create-pool jruby-config)]
      (-> context
          (assoc :pool-context pool-context)
          (assoc :environment-class-info-tags (atom {})))))

  (stop
   [this context]
   (let [{:keys [pool-context]} (tk-services/service-context this)]
     (jruby-core/flush-pool-for-shutdown! pool-context))
   context)

  (borrow-instance
    [this reason]
    (let [{:keys [pool-context]} (tk-services/service-context this)
          event-callbacks (jruby-core/get-event-callbacks pool-context)]
      (jruby-core/borrow-from-pool-with-timeout pool-context reason event-callbacks)))

  (return-instance
    [this jruby-instance reason]
    (let [pool-context (:pool-context (tk-services/service-context this))
          event-callbacks (jruby-core/get-event-callbacks pool-context)]
      (jruby-core/return-to-pool jruby-instance reason event-callbacks)))

  (free-instance-count
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))
          pool         (jruby-core/get-pool pool-context)]
      (jruby-core/free-instance-count pool)))

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

  (get-environment-class-info-cache-generation-id!
   [this env-name]
   (let [environment-class-info (:environment-class-info-tags
                                 (tk-services/service-context this))]
     (core/get-environment-class-info-cache-generation-id!
      environment-class-info
      env-name)))

  (set-environment-class-info-tag!
   [this env-name tag cache-generation-id-before-tag-computed]
   (let [environment-class-info (:environment-class-info-tags
                                 (tk-services/service-context this))]
     (swap! environment-class-info
            core/environment-class-info-cache-updated-with-tag
            env-name
            tag
            cache-generation-id-before-tag-computed)))

  (flush-jruby-pool!
   [this]
   (let [service-context (tk-services/service-context this)
          {:keys [pool-context]} service-context]
     (jruby-core/flush-pool! pool-context)))

  (get-pool-context
   [this]
   (:pool-context (tk-services/service-context this)))

  (register-event-handler
    [this callback-fn]
    (let [pool-context (:pool-context (tk-services/service-context this))]
      (jruby-core/register-event-handler pool-context callback-fn))))

(def #^{:macro true
        :doc "An alias for the jruby-utils' `with-jruby-instance` macro so
             that it is accessible from the service namespace along with the
             rest of the API."}
  with-jruby-instance #'jruby-core/with-jruby-instance)

(def #^{:macro true
        :doc "An alias for the jruby-utils' `with-lock` macro so
             that it is accessible from the service namespace along with the
             rest of the API."}
  with-lock #'jruby-core/with-lock)
