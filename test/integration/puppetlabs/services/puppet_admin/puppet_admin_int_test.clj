(ns puppetlabs.services.puppet-admin.puppet-admin-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [schema.test :as schema-test]
    [me.raynes.fs :as fs]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [puppetlabs.puppetserver.testutils :as testutils :refer
     [ca-cert localhost-cert localhost-key ssl-request-options]]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
    [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/puppet_admin/puppet_admin_int_test")

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(use-fixtures :once
  schema-test/validate-schemas
  (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def endpoints
  ["/environment-cache" "/jruby-pool"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-access-control-test
  (testing "access denied when cert not on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
        app
        {:jruby-puppet  {:gem-path gem-path}
         :puppet-admin  {:client-whitelist ["notlocalhost"]}
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
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
        app
        {:jruby-puppet  {:gem-path gem-path}
         :puppet-admin  {:client-whitelist ["localhost"]}
         :authorization {:version 1 :rules []}}
        (doseq [endpoint endpoints]
          (testing (str "for " endpoint " endpoint")
            (let [response (http-client/delete
                             (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                             ssl-request-options)]
              (is (= 204 (:status response))
                  (ks/pprint-to-string response))))))))

  (testing "access allowed when whitelist disabled and no cert provided"
    (logutils/with-test-logging
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
      app
      {:jruby-puppet  {:gem-path gem-path}
       :puppet-admin  {:authorization-required false}
       :authorization {:version 1 :rules []}}
      (doseq [endpoint endpoints]
        (testing (str "for " endpoint " endpoint")
          (let [response (http-client/delete
                          (str "https://localhost:8140/puppet-admin-api/v1"
                               endpoint)
                          (select-keys ssl-request-options [:ssl-ca-cert]))]
            (is (= 204 (:status response))
                (ks/pprint-to-string response))))))))

  (testing "access denied when cert denied by rule"
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
      app
      {:jruby-puppet {:gem-path gem-path}
       :puppet-admin  nil
       :authorization {:version 1
                       :rules [{:match-request
                                {:path "/puppet-admin-api/v1"
                                 :type "path"}
                                :allow "notlocalhost"
                                :sort-order 1
                                :name "admin api"}]}}
      (logutils/with-test-logging
       (testing "when no encoded characters in uri"
         (doseq [endpoint endpoints]
           (testing (str "for " endpoint " endpoint")
             (let [response (http-client/delete
                             (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                             ssl-request-options)]
               (is (= 403 (:status response))
                   (ks/pprint-to-string response))))))
       (testing "when encoded characters in uri"
         (let [response (http-client/delete
                         (str "https://localhost:8140/pu%70pet-admin-api/"
                              "v1/%65nvironment-cache")
                         ssl-request-options)]
           (is (= 403 (:status response))
               (ks/pprint-to-string response)))))))

  (testing "when cert allowed by rule and whitelist not configured"
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
      app
      {:jruby-puppet {:gem-path gem-path}
       :puppet-admin  nil
       :authorization {:version 1
                       :rules [{:match-request
                                {:path "/puppet-admin-api/v1"
                                 :type "path"}
                                :allow "localhost"
                                :sort-order 1
                                :name "admin api"}]}}
      (testing "access allowed when no encoded characters in uri"
        (doseq [endpoint endpoints]
          (testing (str "for " endpoint " endpoint")
            (let [response (http-client/delete
                            (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                            ssl-request-options)]
              (is (= 204 (:status response))
                  (ks/pprint-to-string response))))))
      (testing "access allowed for appropriate encoded characters in uri"
        (let [response (http-client/delete
                        (str "https://localhost:8140/pu%70pet-admin-api/"
                             "v1/%65nvironment-cache")
                        ssl-request-options)]
          (is (= 204 (:status response))
              (ks/pprint-to-string response))))
      (testing "bad request returned when relative path in uri"
        (let [response (http-client/delete
                        (str "https://localhost:8140/pu%70pet-admin-api/"
                             "v1/%65nvironment-cache/%2E%2E/bad-place")
                        ssl-request-options)]
          (is (= 400 (:status response))
              (ks/pprint-to-string response))))))

  (testing "access allowed when cert allowed by rule and whitelist empty"
    (logutils/with-test-logging
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
      app
      {:jruby-puppet {:gem-path gem-path}
       :puppet-admin {:client-whitelist []}
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
                (ks/pprint-to-string response))))))))

  (testing "server tolerates client specifying an 'Accept: */*' header"
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because the admin endpoint is implemented
       in Clojure."
     app
     {:jruby-puppet {:gem-path gem-path}}
     (doseq [endpoint endpoints]
       (testing (str "for " endpoint " endpoint")
         (let [response (http-client/delete
                         (str "https://localhost:8140/puppet-admin-api/v1" endpoint)
                         (assoc ssl-request-options :headers {"Accept" "*/*"}))]
           (is (= 204 (:status response))
               (ks/pprint-to-string response))))))))

(deftest ^:integration admin-api-pool-timeout-test
  (testing "pool lock timeout results in 503"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
       app
       {:jruby-puppet {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]
                       :flush-timeout 0}}
       ; Borrow a jruby instance so that the timeout of 0 is reached immediately
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             pool-context (jruby-protocol/get-pool-context jruby-service)
             pool (jruby-core/get-pool pool-context)
             borrowed-instance (.borrowItem pool)]
         (try
           (let [response (http-client/delete
                           "https://localhost:8140/puppet-admin-api/v1/jruby-pool"
                           ssl-request-options)]
             (is (= 503 (:status response)))
             (is (= "An attempt to lock the JRubyPool failed with a timeout"
                    (slurp (:body response)))))
           (finally
             (.releaseItem pool borrowed-instance))))))))

;; See 'environment-flush-integration-test'
;; for additional test coverage on the /environment-cache endpoint
