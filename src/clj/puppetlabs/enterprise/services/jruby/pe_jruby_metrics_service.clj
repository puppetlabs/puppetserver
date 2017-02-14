(ns puppetlabs.enterprise.services.jruby.pe-jruby-metrics-service
  (:require [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.enterprise.services.jruby.pe-jruby-metrics-core :as pe-jruby-metrics-core]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.enterprise.services.protocols.jruby-metrics :as jruby-metrics-protocol]
            [puppetlabs.i18n.core :refer [trs]]))

(defn sample-jruby-metrics!
  [jruby-service metrics]
  (log/trace (trs "Sampling JRuby metrics"))
  (pe-jruby-metrics-core/track-free-instance-count!
    metrics
    (jruby-protocol/free-instance-count jruby-service))
  (pe-jruby-metrics-core/track-requested-instance-count! metrics))

;; This function schedules some metrics sampling to happen on a background thread.
;; The reason it is necessary to do this is because the metrics histograms are
;; sample-based, as opposed to time-based, and we are interested in keeping a
;; time-based average for certain metrics.  e.g. if we only updated the
;; "free-instance-count" average when an instance was borrowed or returned, then,
;; if there was a period where there was no load on the server, the histogram
;; would not be getting any updates at all and the average would appear to
;; remain flat, when actually it should be changing (increasing, presumably,
;; because there should be plenty of free jruby instances available in the pool).
(defn schedule-metrics-sampler!
  [jruby-service metrics interspaced]
  (interspaced 5000 (partial sample-jruby-metrics! jruby-service metrics)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(tk/defservice pe-jruby-metrics-service
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
          metrics (pe-jruby-metrics-core/init-metrics
                    metrics-server-id
                    max-active-instances
                    (fn [] (jruby-protocol/free-instance-count jruby-service))
                    (get-metrics-registry :puppetserver))]
      (register-status
        "pe-jruby-metrics"
        (status-core/get-artifact-version "puppetlabs" "puppetserver")
        1
        (partial pe-jruby-metrics-core/v1-status metrics))
      (assoc context :metrics metrics)))

  (start
    [this context]
    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          {:keys [metrics]} (tk-services/service-context this)
          sampler-job-id (schedule-metrics-sampler! jruby-service metrics interspaced)]
      (register-event-handler (partial pe-jruby-metrics-core/jruby-event-callback
                                       metrics))
      (assoc-in context [:metrics :sampler-job-id] sampler-job-id)))

  (get-metrics
    [this]
    (:metrics (tk-services/service-context this)))

  (stop
    [this context]
    (log/info (trs "PE JRuby Metrics Service: stopping metrics sampler job"))
    (stop-job (get-in context [:metrics :sampler-job-id]))
    (log/info (trs "PE JRuby Metrics Service: stopped metrics sampler job"))
    context))
