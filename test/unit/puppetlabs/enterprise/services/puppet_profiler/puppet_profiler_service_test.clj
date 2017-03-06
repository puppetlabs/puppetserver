(ns puppetlabs.enterprise.services.puppet-profiler.puppet-profiler-service-test
  (:import (com.puppetlabs.puppetserver PuppetProfiler))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.services.puppet-profiler.puppet-profiler-service :refer :all]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :refer :all]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.services.protocols.puppet-profiler :as profiler-protocol]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :as app]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-profiler-service
  (testing "Can boot profiler service and access profiler"
    (with-app-with-config app
      [metrics-service metrics-puppet-profiler-service status-service
       jetty9-service webrouting-service scheduler-service]
      {:profiler  {:enabled true}
       :metrics   {:server-id "localhost"}
       :webserver {:port 8080}
       :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}}
      (let [svc (app/get-service app :PuppetProfilerService)]
        (is (instance? PuppetProfiler (profiler-protocol/get-profiler svc)))))))
