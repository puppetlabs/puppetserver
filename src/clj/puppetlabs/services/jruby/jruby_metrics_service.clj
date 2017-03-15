(ns puppetlabs.services.jruby.jruby-metrics-service
  (:require [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.services.protocols.jruby-metrics :as jruby-metrics-protocol]
            [puppetlabs.i18n.core :refer [trs]]))

(def jruby-metrics-service-status-version 1)

;; Default list of allowed histograms/timers
(def default-metrics-allowed-hists
  ["jruby.borrow-timer"
   "jruby.free-jrubies-histo"
   "jruby.lock-held-timer"
   "jruby.lock-wait-timer"
   "jruby.requested-jrubies-histo"
   "jruby.wait-timer"])

;; Default list of allowed values/counts
(def default-metrics-allowed-vals
  ["jruby.borrow-count"
   "jruby.borrow-retry-count"
   "jruby.borrow-timeout-count"
   "jruby.num-free-jrubies"
   "jruby.num-jrubies"
   "jruby.request-count"
   "jruby.return-count"])

(def default-metrics-allowed
  (concat
   default-metrics-allowed-hists
   default-metrics-allowed-vals))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(tk/defservice jruby-metrics-service
  jruby-metrics-protocol/JRubyMetricsService
  [[:ConfigService get-in-config]
   [:JRubyPuppetService register-event-handler]
   [:MetricsService get-metrics-registry get-server-id update-registry-settings]
   [:SchedulerService interspaced stop-job]
   [:StatusService register-status]]
  (init
    [this context]
    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          metrics-server-id (get-server-id)
          max-active-instances (-> (tk-services/get-service this :JRubyPuppetService)
                                 tk-services/service-context
                                 (get-in [:pool-context :config :max-active-instances]))
          metrics (jruby-metrics-core/init-metrics
                    metrics-server-id
                    max-active-instances
                    (fn [] (jruby-protocol/free-instance-count jruby-service))
                    (get-metrics-registry :puppetserver))]
      (update-registry-settings :puppetserver
                                {:default-metrics-allowed default-metrics-allowed})
      (register-status
       "jruby-metrics"
       (status-core/get-artifact-version "puppetlabs" "puppetserver")
       jruby-metrics-service-status-version
       (partial jruby-metrics-core/v1-status metrics))
      (assoc context :metrics metrics)))

  (start
    [this context]
    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          {:keys [metrics]} (tk-services/service-context this)
          sampler-job-id (jruby-metrics-core/schedule-metrics-sampler!
                          jruby-service metrics interspaced)]
      (register-event-handler (partial jruby-metrics-core/jruby-event-callback
                                       metrics))
      (assoc-in context [:metrics :sampler-job-id] sampler-job-id)))

  (get-metrics
    [this]
    (:metrics (tk-services/service-context this)))

  (stop
    [this context]
    (log/info (trs "JRuby Metrics Service: stopping metrics sampler job"))
    (stop-job (get-in context [:metrics :sampler-job-id]))
    (log/info (trs "JRuby Metrics Service: stopped metrics sampler job"))
    context))
