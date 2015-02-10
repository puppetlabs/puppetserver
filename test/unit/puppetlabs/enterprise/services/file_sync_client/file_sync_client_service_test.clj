(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service-test
  (:import (javax.net.ssl SSLHandshakeException))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-utils :as client-utils]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.http.client.common :as http-client]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.enterprise.file-sync-test-utils :as testutils]))

(defn mock-process-repos-for-updates
  [request-url _ _ client]
  (let [response (http-client/get client request-url {:as :text})]
    (is (= 200 (:status response)))
    (is (= "Successful connection over SSL" (:body response)))))

(defn mock-process-repos-for-updates-SSL-failure
  [request-url _ _ client]
  (is (thrown? SSLHandshakeException
               (http-client/get client request-url))))

(defn ring-handler
  [_]
  {:status 200
   :body "Successful connection over SSL"})

(deftest polling-client-ssl-test
  (testing "polling client will use SSL when configured"
    (logging/with-test-logging
      (with-redefs
        [core/process-repos-for-updates mock-process-repos-for-updates]
        (client-utils/with-boostrapped-file-sync-client-and-webserver
          {:ssl-port    10080
           :ssl-host    "0.0.0.0"
           :ssl-ca-cert "./dev-resources/ssl/ca.pem"
           :ssl-cert    "./dev-resources/ssl/cert.pem"
           :ssl-key     "./dev-resources/ssl/key.pem"}
          ring-handler
          {:poll-interval 1
           :server-url    "https://localhost:10080/"
           :repos         [{:name "fake" :target-dir "fake"}]
           :server-api-path testutils/default-api-path-prefix
           :server-repo-path testutils/default-repo-path-prefix
           :ssl-ca-cert   "./dev-resources/ssl/ca.pem"
           :ssl-cert      "./dev-resources/ssl/cert.pem"
           :ssl-key       "./dev-resources/ssl/key.pem"}
          (Thread/sleep 500)))))

  (testing "polling client fails to use SSL when not configured"
    (logging/with-test-logging
      (with-redefs
        [core/process-repos-for-updates mock-process-repos-for-updates-SSL-failure]
        (client-utils/with-boostrapped-file-sync-client-and-webserver
          {:ssl-port    10080
           :ssl-host    "0.0.0.0"
           :ssl-ca-cert "./dev-resources/ssl/ca.pem"
           :ssl-cert    "./dev-resources/ssl/cert.pem"
           :ssl-key     "./dev-resources/ssl/key.pem"}
          ring-handler
          {:poll-interval 1
           :server-url    "https://localhost:10080/"
           :repos         [{:name "fake" :target-dir "fake"}]
           :server-api-path testutils/default-api-path-prefix
           :server-repo-path testutils/default-repo-path-prefix}
          (Thread/sleep 500)))))

  (testing "SSL configuration fails when not all options are provided"
    (logging/with-test-logging
      (with-redefs
        [core/process-repos-for-updates mock-process-repos-for-updates-SSL-failure]
        (client-utils/with-boostrapped-file-sync-client-and-webserver
          {:ssl-port    10080
           :ssl-host    "0.0.0.0"
           :ssl-ca-cert "./dev-resources/ssl/ca.pem"
           :ssl-cert    "./dev-resources/ssl/cert.pem"
           :ssl-key     "./dev-resources/ssl/key.pem"}
          ring-handler
          {:poll-interval 1
           :server-url    "https://localhost:10080/"
           :repos         [{:name "fake" :target-dir "fake"}]
           :server-api-path testutils/default-api-path-prefix
           :server-repo-path testutils/default-repo-path-prefix
           :ssl-cert      "./dev-resources/ssl/cert.pem"
           :ssl-key       "./dev-resources/ssl/key.pem"}
          (Thread/sleep 500)
          (is (logged? #"Not configuring SSL, as only some SSL options were set. ")))))))
