(ns puppetlabs.services.jruby.jruby-metrics-service
  (:require [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.services.protocols.jruby-metrics :as jruby-metrics-protocol]
            [puppetlabs.i18n.core :refer [trs]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(tk/defservice jruby-metrics-service
  jruby-metrics-protocol/JRubyMetricsService
  [[:ConfigService get-in-config]
   [:JRubyPuppetService register-event-handler]
   [:MetricsService get-metrics-registry]
   [:SchedulerService interspaced stop-job]
   [:StatusService register-status]]
  (init
    [this context]
    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          metrics-server-id (get-in-config [:metrics :server-id])
          max-active-instances (-> (tk-services/get-service this :JRubyPuppetService)
                                 tk-services/service-context
                                 (get-in [:pool-context :config :max-active-instances]))
          metrics (jruby-metrics-core/init-metrics
                    metrics-server-id
                    max-active-instances
                    (fn [] (jruby-protocol/free-instance-count jruby-service))
                    (get-metrics-registry :puppetserver))]
      (register-status
        "jruby-metrics"
        (status-core/get-artifact-version "puppetlabs" "puppetserver")
        1
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
