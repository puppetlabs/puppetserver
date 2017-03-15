(ns puppetlabs.services.puppet-profiler.puppet-profiler-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :as puppet-profiler-core]
            [puppetlabs.services.protocols.puppet-profiler :as profiler]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

;; Default list of allowed histograms/timers
(def default-metrics-allowed
  ["compiler"
   "compiler.compile"
   "compiler.find_facts"
   "compiler.find_node"
   "compiler.static_compile"
   "compiler.static_compile_inlining"
   "compiler.static_compile_postprocessing"
   "functions"
   "puppetdb.catalog.save"
   "puppetdb.command.submit"
   "puppetdb.facts.find"
   "puppetdb.facts.search"
   "puppetdb.report.process"
   "puppetdb.resource.search"])


(trapperkeeper/defservice puppet-profiler-service
                          profiler/PuppetProfilerService
                          [[:MetricsService get-metrics-registry get-server-id update-registry-settings]
                           [:ConfigService get-in-config]
                           [:StatusService register-status]]

  (init [this context]
    (let [context (puppet-profiler-core/initialize
                    (get-in-config [:profiler] {})
                    (get-server-id)
                    (get-metrics-registry :puppetserver))]
      (update-registry-settings :puppetserver
                                {:default-metrics-allowed default-metrics-allowed})
      (register-status
        "puppet-profiler"
        (status-core/get-artifact-version "puppetlabs" "puppetserver")
        1
        (partial puppet-profiler-core/v1-status (:profiler context)))
      context))

  (get-profiler
    [this]
    (:profiler (tk-services/service-context this))))
