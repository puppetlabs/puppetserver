(ns puppetlabs.services.puppet-profiler.puppet-profiler-service-test
  (:import (com.puppetlabs.puppetserver PuppetProfiler))
  (:require [clojure.test :refer :all]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :refer :all]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :as metrics-service]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as scheduler-service]
            [puppetlabs.trapperkeeper.services.status.status-service :as status-service]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.services.protocols.puppet-profiler :refer [get-profiler]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.app :as app]))

(defn call-get-profiler
  [config pred?]
  (bootstrap/with-app-with-config
    app
    [puppet-profiler-service
     jetty9-service/jetty9-service
     metrics-service/metrics-service
     scheduler-service/scheduler-service
     status-service/status-service
     webrouting-service/webrouting-service]
    (merge config
           {:metrics {:server-id "localhost"}
            :webserver {:host "localhost"}
            :web-router-service
            {:puppetlabs.trapperkeeper.services.status.status-service/status-service
             "/status"}})
    (let [service (app/get-service app :PuppetProfilerService)]
      (is (pred? (get-profiler service))))))

(deftest test-profiler-service
  (testing "get-profiler returns nil if profiling is not enabled"
    (call-get-profiler {:profiler {:enabled "false"}} nil?)
    (call-get-profiler {:profiler {:enabled false}} nil?))
  (testing "get-profiler returns a profiler if profiling is enabled"
    (call-get-profiler {} #(instance? PuppetProfiler %))
    (call-get-profiler {:profiler {}} #(instance? PuppetProfiler %))
    (call-get-profiler {:profiler {:enabled "true"}} #(instance? PuppetProfiler %))
    (call-get-profiler {:profiler {:enabled true}} #(instance? PuppetProfiler %))))
