(ns puppetlabs.services.puppet-admin.puppet-admin-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [schema.test :as schema-test]
    [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/puppet_admin/puppet_admin_int_test")

(use-fixtures :once
  schema-test/validate-schemas
  (jruby-testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def ca-cert
  (bootstrap/pem-file "certs" "ca.pem"))

(def localhost-cert
  (bootstrap/pem-file "certs" "localhost.pem"))

(def localhost-key
  (bootstrap/pem-file "private_keys" "localhost.pem"))

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

(def endpoints
  ["/environment-cache" "/jruby-pool"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-access-control-test
  (testing "access denied when cert not on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:puppet-admin  {:client-whitelist ["notlocalhost"]}
         :authorization {:version 1 :rules []}}
        (doseq [endpoint endpoints]
          (testing (str "for " endpoint " endpoint")
            (let [response (http-client/delete
                             (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                             ssl-request-options)]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response))))))))

  (testing "access allowed when cert on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:puppet-admin  {:client-whitelist ["localhost"]}
         :authorization {:version 1 :rules []}}
        (doseq [endpoint endpoints]
          (testing (str "for " endpoint " endpoint")
            (let [response (http-client/delete
                             (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                             ssl-request-options)]
              (is (= 204 (:status response))
                  (ks/pprint-to-string response))))))))

  (testing "access denied when cert denied by rule"
    (bootstrap/with-puppetserver-running
      app
      {:puppet-admin  nil
       :authorization {:version 1
                       :rules [{:match-request
                                {:path "/puppet-admin-api/v1"
                                 :type "path"}
                                :allow "notlocalhost"
                                :sort-order 1
                                :name "admin api"}]}}
      (logutils/with-test-logging
        (doseq [endpoint endpoints]
          (testing (str "for " endpoint " endpoint")
            (let [response (http-client/delete
                            (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                            ssl-request-options)]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response))))))))

  (testing "access allowed when cert allowed by rule"
    (bootstrap/with-puppetserver-running
      app
      {:puppet-admin  nil
       :authorization {:version 1
                       :rules [{:match-request
                                {:path "/puppet-admin-api/v1"
                                 :type "path"}
                                :allow "localhost"
                                :sort-order 1
                                :name "admin api"}]}}
      (doseq [endpoint endpoints]
        (testing (str "for " endpoint " endpoint")
          (let [response (http-client/delete
                          (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                          ssl-request-options)]
            (is (= 204 (:status response))
                (ks/pprint-to-string response)))))))

  (testing "server tolerates client specifying an 'Accept: */*' header"
    (bootstrap/with-puppetserver-running app
      {}
      (doseq [endpoint endpoints]
        (testing (str "for " endpoint " endpoint")
          (let [response (http-client/delete
                           (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                           (assoc ssl-request-options :headers {"Accept" "*/*"}))]
            (is (= 204 (:status response))
                (ks/pprint-to-string response))))))))

;; See 'environment-flush-integration-test'
;; for additional test coverage on the /environment-cache endpoint
