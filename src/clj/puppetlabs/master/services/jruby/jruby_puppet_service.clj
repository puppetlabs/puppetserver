(ns puppetlabs.master.services.jruby.jruby-puppet-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.master.services.jruby.jruby-puppet-core :as core]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.master.services.protocols.jruby-puppet :as jruby]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; This service uses TK's normal config service instead of the
;; JvmPuppetConfigService.  This is because that service depends on this one.

(trapperkeeper/defservice jruby-puppet-pooled-service
                          jruby/JRubyPuppetService
                          [[:ConfigService get-in-config]
                           [:ShutdownService shutdown-on-error]
                           [:PuppetProfilerService get-profiler]]
  (init
    [this context]
    (let [config    (get-in-config [:jruby-puppet])]
      (log/info "Initializing the JRuby service")
      (let [pool-context (core/create-pool-context config (get-profiler))
            default-pool-descriptor (core/extract-default-pool-descriptor config)]
        (future
          (shutdown-on-error
            (service-id this)
            #(core/prime-pools! pool-context)))

        (assoc context :pool-context pool-context
                       :default-pool-descriptor default-pool-descriptor))))

  (borrow-instance
    [this pool-desc]
    (let [pool-context (:pool-context (service-context this))]
      (core/borrow-from-pool pool-context pool-desc)))

  (borrow-instance
    [this pool-desc timeout]
    (let [pool-context (:pool-context (service-context this))]
      (core/borrow-from-pool-with-timeout pool-context pool-desc timeout)))

  (return-instance
    [this pool-desc jruby-instance]
    (let [pool-context (:pool-context (service-context this))]
      (core/return-to-pool pool-context pool-desc jruby-instance)))

  (free-instance-count
    [this pool-desc]
    (let [pool-context (:pool-context (service-context this))]
      (core/free-instance-count pool-context pool-desc)))

  (get-default-pool-descriptor
    [this]
    (-> (service-context this)
        (:default-pool-descriptor))))

(defmacro with-jruby-puppet
  "Encapsulates the behavior of borrowing and returning an instance of
  JRubyPuppet.  Example usage:

  (let [jruby-service (get-service :JRubyPuppetService)]
    (with-jruby-puppet
      jruby-puppet
      jruby-service
      pool-descriptor
      (do-something-with-a-jruby-puppet-instance jruby-puppet)))"
  [jruby-puppet jruby-service pool-descriptor & body]
  `(let [~jruby-puppet (jruby/borrow-instance ~jruby-service ~pool-descriptor)]
     (try
       ~@body
       (finally
         (jruby/return-instance ~jruby-service ~pool-descriptor ~jruby-puppet)))))
