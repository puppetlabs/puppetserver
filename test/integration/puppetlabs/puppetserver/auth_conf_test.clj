(ns puppetlabs.puppetserver.auth-conf-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

(def test-resources-dir
  "./dev-resources/puppetlabs/puppetserver/auth_conf_test")

(use-fixtures
  :once
  schema-test/validate-schemas
  (jruby-testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

(defn http-get [path]
  (http-client/get
    (str "https://localhost:8140/" path)
    bootstrap/request-options))

(deftest ^:integration legacy-auth-conf-used-when-legacy-auth-conf-true
  (testing "Authorization is done per legacy auth.conf when :use-legacy-auth-conf true"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet {:use-legacy-auth-conf true}}
        (logutils/with-test-logging
          (testing "for puppet 4 routes"
            (let [response (http-get "puppet/v3/node/public?environment=production")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "puppet/v3/node/private?environment=production")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "puppet/v3/catalog/public?environment=production")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "puppet/v3/catalog/private?environment=production")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response))))
          (testing "for legacy puppet routes"
            (let [response (http-get "production/node/public")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "production/node/private")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "production/catalog/public")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "production/catalog/private")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))))))))

(deftest ^:integration tk-auth-used-when-legacy-auth-conf-false
  (testing "Authorization is done per trapperkeeper auth.conf when :use-legacy-auth-conf false"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet  {:use-legacy-auth-conf false}
         :authorization {:version 1
                         :rules
                         [{:match-request {:path "^/puppet/v3/catalog/private$"
                                           :type "regex"}
                           :allow         ["private" "localhost"]
                           :sort-order 1
                           :name "catalog"}
                          {:match-request {:path "^/puppet/v3/node/private$"
                                           :type "regex"}
                           :allow         ["private" "localhost"]
                           :sort-order 1
                           :name "node"}]}}
        (logutils/with-test-logging
          (testing "for puppet 4 routes"
            (let [response (http-get "puppet/v3/node/public?environment=production")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "puppet/v3/node/private?environment=production")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "puppet/v3/catalog/public?environment=production")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "puppet/v3/catalog/private?environment=production")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response))))
          (testing "for legacy puppet routes"
            (let [response (http-get "production/node/public")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "production/node/private")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "production/catalog/public")]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response (http-get "production/catalog/private")]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))))))))
