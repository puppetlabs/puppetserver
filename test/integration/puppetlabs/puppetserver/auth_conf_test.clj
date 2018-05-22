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
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.testutils :as testutils :refer [http-get]]
            [me.raynes.fs :as fs]
            [ring.util.codec :as ring-codec]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [cheshire.core :as cheshire])
  (:import (java.io StringWriter)))

(def test-resources-dir
  (ks/absolute-path "./dev-resources/puppetlabs/puppetserver/auth_conf_test"))

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

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
        {:jruby-puppet
          {:use-legacy-auth-conf true
           :gem-path gem-path}}
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
        {:jruby-puppet
          {:use-legacy-auth-conf false
           :gem-path gem-path}
         :authorization {:version 1
                         :allow-header-cert-info false
                         :rules
                         [{:match-request {:path "^/puppet/v3/catalog/private$"
                                           :type "regex"}
                           :allow         ["private" "localhost"]
                           :sort-order 1
                           :name "catalog unencoded"}
                          {:match-request {:path "^/puppet/v3/node/private$"
                                           :type "regex"}
                           :allow         ["private" "localhost"]
                           :sort-order 1
                           :name "node"}
                          {:match-request {:path
                                           "^/puppet/v3/catalog/%65ncoded$"
                                           :type
                                           "regex"}
                           :allow         ["localhost"]
                           :sort-order 1
                           :name "catalog encoded"}]}}
        (logutils/with-test-logging
          (testing "for puppet 4 routes without url encoding"
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
                  (ks/pprint-to-string response))
              (is (testutils/catalog-name-matches?
                   (testutils/catalog-ring-response->catalog response)
                   "private"))))
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
                  (ks/pprint-to-string response))
              (is (testutils/catalog-name-matches?
                   (testutils/catalog-ring-response->catalog response)
                   "private"))))
          (testing "for puppet 4 routes with url encoding"
            (let [response
                  (http-get
                   "puppet/v3/%63atalog/%65ncoded?environment=production")]
              ;; The web server should decode the above URI path component to
              ;; "puppet/v3/catalog/encoded".  There is a rule allowing
              ;; "localhost" for "%65ncoded" but no rule allowing "localhost"
              ;; for "encoded", so this should request should fail with a
              ;; 403 (Forbidden) error.
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response
                  (http-get
                   "puppet/v3/%63atalog/%2565ncoded?environment=production")]
              ;; The web server should decode the above URI path component to
              ;; "puppet/v3/catalog/%65ncoded".  There is a rule allowing
              ;; "localhost" for "%65ncoded", so this should request should
              ;; succeed.
              (is (= 200 (:status response))
                  (ks/pprint-to-string response))
              ;; The catalog which is returned should have a name of "%65ncoded"
              ;; since this should be the name derived from the web server
              ;; request after a single percent-decode.
              (is (testutils/catalog-name-matches?
                   (testutils/catalog-ring-response->catalog response)
                   "%65ncoded")))
            (let [response
                  (http-get
                   (str "puppet/v3/%63atalog/private/%2E%2E/secret?"
                        "environment=production"))]
              ;; The web server should decode the above URI path component to
              ;; "puppet/v3/catalog/private/../secret".  The relative
              ;; path, "/../", inside of the path component is forbidden and
              ;; so should cause the webserver to throw a 'Bad Request' error.
              (is (= 400 (:status response))
                  (ks/pprint-to-string response))))
          (testing "for legacy puppet routes with url encoding"
            (let [response
                  (http-get "production/%63atalog/%65ncoded")]
              ;; The web server should decode the above URI path component to
              ;; "production/catalog/encoded".  There is a rule allowing
              ;; "localhost" for "%65ncoded" but no rule allowing "localhost"
              ;; for "encoded", so this should request should fail with a
              ;; 403 (Forbidden) error.
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))
            (let [response
                  (http-get "production/%63atalog/%2565ncoded")]
              ;; The web server should decode the above URI path component to
              ;; "production/catalog/%65ncoded".  There is a rule allowing
              ;; "localhost" for "%65ncoded", so this should request should
              ;; succeed.
              (is (= 200 (:status response))
                  (ks/pprint-to-string response))
              ;; The catalog which is returned should have a name of "%65ncoded"
              ;; since this should be the name derived from the web server
              ;; request after a single percent-decode.
              (is (testutils/catalog-name-matches?
                   (testutils/catalog-ring-response->catalog response)
                   "%65ncoded")))
            (let [response
                  (http-get "production/%63atalog/private/%2E%2E/secret?")]
              ;; The web server should decode the above URI path component to
              ;; "production/catalog/private/../secret".  The relative
              ;; path, "/../", inside of the path component is forbidden and
              ;; so should cause the webserver to throw a 'Bad Request' error.
              (is (= 400 (:status response))
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
                             {:headers {"Accept" "application/json"
                                        "X-Client-Cert" url-encoded-cert
                                        "X-Client-DN" "CN=private"
                                        "X-Client-Verify" "SUCCESS"}
                              :as :text}))]
      (logutils/with-test-logging
       (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet  {:use-legacy-auth-conf false
                         :gem-path gem-path}
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
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "JRuby mocking is safe here because the static_file_content endpoint is
      implemented in Clojure."
      app
      {:jruby-puppet {:use-legacy-auth-conf true
                      :gem-path gem-path}
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

(deftest ^:integration custom-oids-passed-to-tk-auth
  (testing "puppet server successfully utilizes custom oid mappings and puppet short names for authorization"
    (logging/with-test-logging
      (bootstrap/with-puppetserver-running-with-mock-jruby-puppet-fn
       "JRuby mocking is safe here because these tests are strictly validating
       the Clojure tk-auth checks."
        app
        {:jruby-puppet  {:gem-path gem-path}
         :authorization {:version 1
                         :allow-header-cert-info true
                         :rules
                         [{:match-request {:path "/"
                                           :type "path"}
                           :allow {:extensions {:shiningfinger "Burning Finger"}}
                           :deny {:extensions {:pp_uuid "not a uuid"}}
                           :sort-order 1
                           :name "all endpoints"}]}
         :webserver {:host "localhost"
                     :port 8080}
         :puppet {"csr_attributes" (str test-resources-dir "/csr_attributes.yaml")
                  "trusted_oid_mapping_file" (str test-resources-dir
                                                  "/custom_trusted_oid_mapping.yaml")}}
       (jruby-testutils/create-mock-jruby-puppet-fn-with-handle-response-params
        200
        "we've been tk-authorized!")
        (let [good-exts [{:oid "1.3.6.1.4.1.34380.1.1.1"
                          :critical false
                          :value "12345"}
                         {:oid "1.3.6.1.4.1.34380.1.2.2"
                          :critical false
                          :value "Burning Finger"}]
              bad-exts [{:oid "1.3.6.1.4.1.34380.1.1.1"
                         :critical false
                         :value "not a uuid"}
                        {:oid "1.3.6.1.4.1.34380.1.2.2"
                         :critical false
                         :value "Burning Finger"}]
              create-cert (fn [extensions]
                            (:cert (ssl-simple/gen-self-signed-cert
                                     "ssl-client"
                                     1
                                     {:keylength 512
                                      :extensions extensions})))
              allowable-cert (create-cert good-exts)
              deniable-cert (create-cert bad-exts)
              url-encode-cert (fn [cert]
                                (let [cert-writer (StringWriter.)
                                      _ (ssl-utils/cert->pem! cert cert-writer)]
                                  (ring-codec/url-encode cert-writer)))
              url-encoded-allowable-cert (url-encode-cert allowable-cert)
              url-encoded-deniable-cert (url-encode-cert deniable-cert)
              http-get-no-ssl (fn [path cert]
                                (http-client/get
                                  (str "http://localhost:8080/" path)
                                  {:headers {"Accept" "application/json"
                                             "X-Client-Cert" cert
                                             "X-Client-DN" "CN=private"
                                             "X-Client-Verify" "SUCCESS"}
                                   :as :text}))]
          (testing "ca endpoints use oid shortnames"
            (let [path "/puppet-ca/v1/certificate_status/localhost"
                  cert-status-response (http-get-no-ssl path url-encoded-allowable-cert)
                  cert-status-body (-> cert-status-response
                                       :body
                                       cheshire/parse-string)]
              (is (= 200 (:status cert-status-response)))
              ;; Assert that some of the content looks like it came from the
              ;; certificate_statuses endpoint
              (is (= "localhost" (get cert-status-body "name")))
              (is (= "signed" (get cert-status-body "state")))
              (is (= 403 (:status (http-get-no-ssl path url-encoded-deniable-cert))))))

          (testing "master endpoints use oid shortnames"
            (let [path "puppet/v3/catalog/private?environment=production"
                  catalog-response (http-get-no-ssl path url-encoded-allowable-cert)]
              (is (= 200 (:status catalog-response)))
              (is (= "we've been tk-authorized!" (:body catalog-response)))
              (is (= 403 (:status (http-get-no-ssl path url-encoded-deniable-cert))))))

          (testing "legacy endpoints support oid shortnames"
            (let [path "/v2.0/environments"
                  env-response (http-get-no-ssl path url-encoded-allowable-cert)]
              (is (= 200 (:status env-response)))
              (is (= "we've been tk-authorized!" (:body env-response)))
              (is (= 403 (:status (http-get-no-ssl path url-encoded-deniable-cert))))))

          (testing "legacy CA endpoints support oid shortnames"
            (let [path "/production/certificate_status/localhost"
                  cert-status-response (http-get-no-ssl path url-encoded-allowable-cert)
                  cert-status-body (-> cert-status-response
                                       :body
                                       cheshire/parse-string)]
              (is (= 200 (:status cert-status-response)))
              ;; Assert that some of the content looks like it came from the
              ;; certificate_statuses endpoint
              (is (= "localhost" (get cert-status-body "name")))
              (is (= "signed" (get cert-status-body "state")))
              (is (= 403 (:status (http-get-no-ssl path url-encoded-deniable-cert))))))

          (testing "puppet-admin endpoints support oid shortnames"
            (let [path "/puppet-admin-api/v1/environment-cache?environment=production"
                  http-delete (fn [cert]
                                (http-client/delete
                                  (str "http://localhost:8080/" path)
                                  {:headers {"Accept" "application/json"
                                             "X-Client-Cert" cert
                                             "X-Client-DN" "CN=private"
                                             "X-Client-Verify" "SUCCESS"}
                                   :as :text}))]
              (is (= 204 (:status (http-delete url-encoded-allowable-cert))))
              (is (= 403 (:status (http-delete url-encoded-deniable-cert)))))))))))
