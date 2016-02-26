(ns puppetlabs.puppetserver.auth-conf-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.ssl-utils.simple :as ssl-simple]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.testutils :as testutils :refer [http-get]]
            [me.raynes.fs :as fs]
            [ring.util.codec :as ring-codec]
            [puppetlabs.trapperkeeper.testutils.logging :as logging])
  (:import (java.io StringWriter)))

(def test-resources-dir
  (ks/absolute-path "./dev-resources/puppetlabs/puppetserver/auth_conf_test"))

(defn script-path
  [script-name]
  (str test-resources-dir "/" script-name))

(use-fixtures
  :once
  schema-test/validate-schemas
  (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

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

(deftest ^:integration request-with-ssl-cert-handled-via-tk-auth
  (testing (str "Request with SSL certificate via trapperkeeper-authorization "
                "handled")
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet  {:use-legacy-auth-conf false}
         :authorization {:version 1
                         :allow-header-cert-info false
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

(deftest ^:integration request-with-x-client-headers-handled-via-tk-auth
  (testing (str "Request with X-Client headers via trapperkeeper-authorization "
                "handled")
    (let [extension-value "UUUU-IIIII-DDD"
          cert (:cert (ssl-simple/gen-self-signed-cert
                       "ssl-client"
                       1
                       {:keylength 512
                        :extensions [{:oid "1.3.6.1.4.1.34380.1.1.1"
                                      :critical false
                                      :value extension-value}]}))
          url-encoded-cert (let [cert-writer (StringWriter.)
                                 _ (ssl-utils/cert->pem! cert cert-writer)]
                             (ring-codec/url-encode cert-writer))
          http-get-no-ssl (fn [path]
                            (http-client/get
                             (str "http://localhost:8080/" path)
                             {:headers {"Accept" "pson"
                                        "X-Client-Cert" url-encoded-cert
                                        "X-Client-DN" "CN=private"
                                        "X-Client-Verify" "SUCCESS"}
                              :as :text}))]
      (logutils/with-test-logging
       (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet  {:use-legacy-auth-conf false}
         :authorization {:version 1
                         :allow-header-cert-info true
                         :rules
                         [{:match-request {:path "^/puppet/v3/catalog/private$"
                                           :type "regex"}
                           :allow ["private" "localhost"]
                           :sort-order 1
                           :name "catalog"}]}
         :webserver     {:host "localhost"
                         :port 8080}}
        (testing "as 403 for unauthorized user"
          (logutils/with-test-logging
           (let [response (http-get-no-ssl
                           "puppet/v3/catalog/public?environment=production")]
             (is (= 403 (:status response))
                 (ks/pprint-to-string response)))))
        (testing "for certificate when provided"
          (let [environment-dir (fs/file jruby-testutils/code-dir
                                         "environments")
                manifest-dir (fs/file environment-dir
                                      "production"
                                      "manifests")]
            (try
              (fs/mkdirs manifest-dir)
              (spit (fs/file manifest-dir "site.pp")
                    (str/join "\n"
                              ["notify {'trusty_info':"
                               "  message => $trusted[extensions][pp_uuid]"
                               "}\n"]))
              (let [response
                    (http-get-no-ssl
                     "puppet/v3/catalog/private?environment=production")
                    expected-content-in-catalog
                    (str
                     "\"parameters\":{\"message\":\""
                     extension-value
                     "\"}")]
                (is (= 200 (:status response))
                    (ks/pprint-to-string response))
                (is (.contains (:body response) expected-content-in-catalog)
                    (str "Did not find '" expected-content-in-catalog
                         "' in full response body: " (:body response))))
              (finally
                (fs/delete-dir environment-dir))))))))))

(deftest ^:integration static-file-content-works-with-legacy-auth
  (testing "The static_file_content endpoint works even if legacy-auth is enabled."
    ;; with-test-logging is used here to suppress a warning about running with legacy auth enabled.
    (logging/with-test-logging
     (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet {:use-legacy-auth-conf true}
       :authorization {:version 1
                       :rules
                       [{:match-request {:path "/puppet/v3/static_file_content"
                                         :type "path"}
                         :allow ["private" "localhost"]
                         :sort-order 1
                         :name "static file content"}]}
       :versioned-code
       {:code-content-command (script-path "echo")
        :code-id-command (script-path "echo")}}
      (testing "for legacy puppet routes with a valid cert"
        (let [response (testutils/get-static-file-content
                        "modules/foo/files/bar?code_id=foobar&environment=test")]
          (is (= 200 (:status response)) (ks/pprint-to-string response))
          (is (= "test foobar modules/foo/files/bar\n" (:body response)) (ks/pprint-to-string response))))
      (testing "for legacy puppet routes without a valid cert"
        (let [response (testutils/get-static-file-content
                        "modules/foo/files/bar?code_id=foobar&environment=test" false)]
          (is (= 403 (:status response)) (ks/pprint-to-string response))
          (is (re-find #"Forbidden request" (:body response)) (ks/pprint-to-string response))))))))
