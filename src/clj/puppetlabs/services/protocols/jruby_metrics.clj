(ns puppetlabs.services.protocols.jruby-metrics)

(defprotocol JRubyMetricsService
  (get-metrics [this]
    "Get the current map of JRuby-related metrics"))
