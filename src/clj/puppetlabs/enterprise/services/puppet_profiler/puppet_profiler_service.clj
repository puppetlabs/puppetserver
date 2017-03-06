(ns puppetlabs.enterprise.services.puppet-profiler.puppet-profiler-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.enterprise.services.puppet-profiler.puppet-profiler-core :as puppet-profiler-core]
            [puppetlabs.services.protocols.puppet-profiler :as profiler]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

(trapperkeeper/defservice metrics-puppet-profiler-service
                          profiler/PuppetProfilerService
                          [[:MetricsService get-metrics-registry]
                           [:ConfigService get-in-config]
                           [:StatusService register-status]]

  (init [this context]
    (let [context (puppet-profiler-core/initialize
                    (get-in-config [:profiler] {})
                    (get-in-config [:metrics :server-id])
                    (get-metrics-registry :puppetserver))]
      (register-status
        "pe-puppet-profiler"
        (status-core/get-artifact-version "puppetlabs" "puppetserver")
        1
        (partial puppet-profiler-core/v1-status (:profiler context)))
      context))

  (get-profiler
    [this]
    (:profiler (tk-services/service-context this))))
