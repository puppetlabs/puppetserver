(ns puppetlabs.services.master.plans-int-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as json]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  (ks/absolute-path "./dev-resources/puppetlabs/services/master/plans_int_test"))

(defn plan-path
  [plan-name]
  (str test-resources-dir "/" plan-name))

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

(def request-as-text-with-ssl (assoc testutils/ssl-request-options :as :text))

(defn get-all-plans
  [env-name]
  (let [url (str "https://localhost:8140/puppet/v3/plans"
                 (when env-name (str "?environment=" env-name)))]
    (try
      (http-client/get url request-as-text-with-ssl)
      (catch Exception e
        (throw (Exception. "plans http get failed" e))))))

(defn get-plan-details
  "plan-name is the plan's full name, e.g. 'apache::reboot'."
  [env-name full-plan-name]
  (let [[module-name plan-name]  (str/split full-plan-name #"::")
        url (str "https://localhost:8140/puppet/v3/plans/" module-name "/" plan-name
                 (when env-name (str "?environment=" env-name)))]
    (try
      (http-client/get url request-as-text-with-ssl)
      (catch Exception e
        (throw (Exception. "plan info http get failed" e))))))

(defn parse-response
  [response]
  (-> response :body json/parse-string))

(defn sort-plans
  [plans]
  (sort-by #(get % "name") plans))

(def puppet-config
  (-> (bootstrap/load-dev-config-with-overrides {:jruby-puppet
                                                 {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]
                                                  :max-active-instances 1}})
      (ks/dissoc-in [:jruby-puppet
                     :environment-class-cache-enabled])))

(deftest ^:integration all-plans-with-env
  (testing "full stack plans listing smoke test"
    (bootstrap/with-puppetserver-running-with-config
      app
      puppet-config
      (do
        (testutils/write-plans-files "apache" "announce" "return 'Hi!'")
        (testutils/write-plans-files "graphite" "init" "return 'Wheeeee'")
        (let [expected-response '({"name" "apache::announce"
                                   "environment" [{"name" "production"
                                                   "code_id" nil}]}
                                  {"name" "graphite"
                                   "environment" [{"name" "production"
                                                   "code_id" nil}]})
              response (get-all-plans "production")]
          (testing "a successful status code is returned"
            (is (= 200 (:status response))
                (str
                  "unexpected status code for response, response: "
                  (ks/pprint-to-string response))))
          (testing "the expected response body is returned"
            (is (= expected-response
                   (sort-plans (parse-response response))))))))))

(deftest ^:integration plan-details
  (testing "full stack plan metadata smoke test:"
    (bootstrap/with-puppetserver-running-with-config
      app
      puppet-config
      (let [metadata {}]
        (testutils/write-plans-files "shell" "poc" "String $message" "return $message")

        (testing "on a successful request,"
          (let [response (get-plan-details "production" "shell::poc")
                code (:status response)]
            (testing "a successful status code is returned"
              (is (= 200 code)
                  (str "unexpected status code " code " for response: "
                       (ks/pprint-to-string response))))

            (testing "the expected response body is returned"
              (let [expected-response {"metadata" metadata
                                       "name" "shell::poc"}]
                (is (= expected-response (parse-response response)))))))

        (testing "on a request that should error,"
          (let [assert-plan-error (fn [status pattern env full-plan-name]
                               (let [result (get-plan-details env full-plan-name)]
                                 (is (= status (:status result)))
                                 (is (re-find pattern (:body result)))))]

            (testing "returns 404 when the environment does not exist"
              (assert-plan-error 404 #"Could not find environment"
                                 "nopers" "shell::poc"))

            (testing "returns 404 when the module does not exist"
              (assert-plan-error 404 #"Could not find module"
                                 "production" "nomodule::poc"))

            (testing "returns 404 when the module name is invalid"
              (assert-plan-error 404 #"Could not find module"
                                 "production" "000::poc"))

            (testing "returns 404 when the plan does not exist"
              (assert-plan-error 404 #"Could not find plan"
                                 "production" "shell::noplan"))

            (testing "returns 404 when the plan name is invalid"
              (assert-plan-error 404 #"Could not find plan"
                                 "production" "shell::..."))))))))
