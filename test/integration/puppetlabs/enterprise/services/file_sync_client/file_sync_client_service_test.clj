(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers])
  (:import (javax.net.ssl SSLException)))

(use-fixtures :once schema-test/validate-schemas)

(def file-sync-client-ssl-config
  (helpers/file-sync-client-config-payload {:fake "fake"} true))

(defn ring-handler
  [_]
  {:status  200
   :body    (json/encode {:result "Successful connection over SSL"})
   :headers {"content-type" "application/json"}})

(deftest ^:integration polling-client-ssl-test
  (testing "polling client will use SSL when configured"
    (logging/with-test-logging
      (helpers/with-bootstrapped-file-sync-client-and-webserver
        app
        helpers/webserver-ssl-config
        ring-handler
        file-sync-client-ssl-config
        (let [sync-agent (helpers/get-sync-agent app)]
          (let [new-state (helpers/wait-for-new-state sync-agent)]
            (is (= :successful (:status new-state)))))))))

(deftest ^:integration polling-client-no-ssl-test
  (testing "polling client fails to use SSL when not configured"
    (logging/with-test-logging
      (helpers/with-bootstrapped-file-sync-client-and-webserver
        app
        helpers/webserver-ssl-config
        ring-handler
        (dissoc file-sync-client-ssl-config :ssl-ca-cert :ssl-cert :ssl-key)
        (let [sync-agent (helpers/get-sync-agent app)
              new-state (helpers/wait-for-new-state sync-agent)]
          (is (= :failed (:status new-state)))
          (is (instance? SSLException (:cause (:error new-state)))))))))

(deftest ^:integration ssl-config-test
  (testing "SSL configuration fails when not all options are provided"
    (logging/with-test-logging
      (is (thrown? IllegalArgumentException
                   (helpers/with-bootstrapped-file-sync-client-and-webserver
                     app
                     helpers/webserver-ssl-config
                     ring-handler
                     (dissoc file-sync-client-ssl-config :ssl-ca-cert)))))))
