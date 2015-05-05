(ns puppetlabs.puppetserver.bootstrap-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(use-fixtures :each logging/reset-logging-config-after-test)

(deftest ^:integration test-app-startup
  (testing "Trapperkeeper can be booted successfully using the dev config files."
    (bootstrap/with-puppetserver-running app {}
      ;; this is just here to validate that the server came up successfully
      (is (true? true)))
    (is (true? true))))