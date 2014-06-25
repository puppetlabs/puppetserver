(ns puppetlabs.master.services.puppet-profiler.puppet-profiler-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.master.services.puppet-profiler.puppet-profiler-core :as core]
            [puppetlabs.master.services.protocols.puppet-profiler :as profiler-prot]))

(trapperkeeper/defservice puppet-profiler-service
                          profiler-prot/PuppetProfilerService
                          [[:ConfigService get-in-config]]
  (get-profiler
    [this]
    (core/create-profiler (get-in-config [:profiler]))))


