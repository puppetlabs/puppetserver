(ns puppetlabs.services.bootstrap-test
  (:import (java.io IOException)
           (org.apache.http ConnectionClosedException))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.testutils :refer [with-no-jvm-shutdown-hooks]]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap
              :as tk-bootstrap-testutils]
            [puppetlabs.trapperkeeper.testutils.webserver.common
              :as tk-webserver-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(use-fixtures :each logging/reset-logging-config-after-test)

(def dev-config-file
  "./dev/sample-configs/puppet-server.sample.conf")

(def dev-bootstrap-file
  "./dev/bootstrap.cfg")

(def dev-bootstrap-no-ca-file
  "./dev/bootstrap-no-ca.cfg")

(deftest test-app-startup
  (testing "Trapperkeeper can be booted successfully using the dev config files."
    (doseq [bootstrap-file [dev-bootstrap-file
                            dev-bootstrap-no-ca-file]]
      (with-no-jvm-shutdown-hooks
        (let [config (tk-config/load-config dev-config-file)
              services (tk-bootstrap/parse-bootstrap-config! bootstrap-file)]
          (->
            (tk/build-app services config)
            (tk-internal/throw-app-error-if-exists!))))
      (is (true? true)))))


(defn validate-connection-failure
  [f]
  (try
    (f)
    (is false "Connection succeeded but should have failed")
    (catch ConnectionClosedException e)
    (catch IOException e
      (if-not (= (.getMessage e) "Connection reset by peer")
        (throw e))))
  nil)