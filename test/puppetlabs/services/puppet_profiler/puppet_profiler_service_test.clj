(ns puppetlabs.services.puppet-profiler.puppet-profiler-service-test
  (:import (com.puppetlabs.puppetserver PuppetProfiler))
  (:require [clojure.test :refer :all]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :refer :all]
            [puppetlabs.services.protocols.puppet-profiler :refer [get-profiler]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.app :as app]))

(defn call-get-profiler
  [config pred?]
  (bootstrap/with-app-with-config
    app
    [puppet-profiler-service]
    config
    (let [service (app/get-service app :PuppetProfilerService)]
      (is (pred? (get-profiler service))))))

(deftest test-profiler-service
  (testing "get-profiler returns nil if profiling is not enabled"
    (call-get-profiler {} nil?)
    (call-get-profiler {:profiler {}} nil?)
    (call-get-profiler {:profiler {:enabled "false"}} nil?)
    (call-get-profiler {:profiler {:enabled false}} nil?))
  (testing "get-profiler returns a profiler if profiling is enabled"
    (call-get-profiler {:profiler {:enabled "true"}} #(instance? PuppetProfiler %))
    (call-get-profiler {:profiler {:enabled true}} #(instance? PuppetProfiler %))))