(ns puppetlabs.services.master.environment-modules-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/environment_modules_int_test")

(defn purge-env-dir
  []
  (-> testutils/conf-dir
      (fs/file "environments")
      fs/delete-dir))

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(use-fixtures :each
              (fn [f]
                (purge-env-dir)
                (try
                  (f)
                  (finally
                    (purge-env-dir)))))

(defn get-env-modules
  [env-name]
  (try
    (http-client/get
      (str "https://localhost:8140/puppet/v3/"
           "environment_modules?"
           "environment="
           env-name)
      (merge
        testutils/ssl-request-options
        {:as :text}))
    (catch Exception e
      (throw (Exception. "environment_modules http get failed" e)))))

(defn get-all-env-modules
  []
  (try
    (http-client/get
      (str "https://localhost:8140/puppet/v3/"
           "environment_modules")
      (merge
        testutils/ssl-request-options
        {:as :text}))
    (catch Exception e
      (throw (Exception. "environment_modules http get failed" e)))))

(defn response->module-info-map
  [response]
  (-> response :body cheshire/parse-string))

(deftest ^:integration environment-modules-integration-cache-disabled-test
  (testing "when environment modules cache is disabled for a module request"
    (bootstrap/with-puppetserver-running-with-config
     app
     (-> {:jruby-puppet {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]
                         :max-active-instances 1}}
         (bootstrap/load-dev-config-with-overrides)
         (ks/dissoc-in [:jruby-puppet
                        :environment-class-cache-enabled]))
     (logutils/with-test-logging
       (let [foo-file (testutils/write-foo-pp-file
                        "class foo (String $foo_1 = \"is foo\"){}")
             all-foo-file (testutils/write-pp-file
                            "class foo (String $foo_1 = \"is foo\"){}"
                            "foo"
                            "woo"
                            "hoo"
                            "1.0.0")
             expected-response {"modules" [{"name" "foo", "version" "1.0.0"}]
                                "name" "production"}
             expected-all-response '({"modules"
                                      [{"name" "foo" "version" "1.0.0"}]
                                      "name" "hoo"}
                                     {"modules"
                                      [{"name" "foo" "version" "1.0.0"}]
                                      "name" "production"})
             response (get-env-modules "production")
             all-response (get-all-env-modules)
             emptyenv-response (get-env-modules "")
             notreal-response (get-env-modules "notreal")]
         (testing "a successful status code is returned"
           (is (= 200 (:status response))
               (str
                 "unexpected status code for response, response: "
                 (ks/pprint-to-string response))))
         (testing "a failed status code is returned"
           (is (= 404 (:status notreal-response))
               (str
                 "unexpected status code for response, response: "
                 (ks/pprint-to-string notreal-response))))
         (testing "a failed status code is returned"
           (is (= 400 (:status emptyenv-response))
               (str
                 "unexpected status code for response, response: "
                 (ks/pprint-to-string emptyenv-response))))
         (testing "the expected response body is returned"
           (is (= expected-response
                  (response->module-info-map response))))
         (testing "a successful status code is returned"
           (is (= 200 (:status all-response))
               (str
                 "unexpected status code for response, response: "
                 (ks/pprint-to-string all-response))))
         (testing "the expected response body is returned"
           (is (= expected-all-response
                  (response->module-info-map all-response)))))))))
