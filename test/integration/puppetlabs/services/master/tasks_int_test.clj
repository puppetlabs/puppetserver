(ns puppetlabs.services.master.tasks-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/tasks_int_test")

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

(defn get-all-tasks
  [env-name]
  (let [url (str "https://localhost:8140/puppet/v3/"
                 "tasks")
        url (if env-name
              (str url "?environment=" env-name)
              url)]
    (try
      (http-client/get url
        (merge
          testutils/ssl-request-options
          {:as :text}))
      (catch Exception e
        (throw (Exception. "tasks http get failed" e))))))

(defn parse-response
  [response]
  (-> response :body cheshire/parse-string))

(defn sort-tasks
  [tasks]
  (sort-by #(get % "name") tasks))

(deftest ^:integration all-tasks-with-env
  (testing "full stack smoke test"
    (bootstrap/with-puppetserver-running-with-config
      app
      (-> {:jruby-puppet {:max-active-instances 1}}
          (bootstrap/load-dev-config-with-overrides)
          (ks/dissoc-in [:jruby-puppet
                         :environment-class-cache-enabled]))
      (let [foo-file (testutils/write-tasks-files "apache" "announce" "echo 'Hi!'")
            bar-file (testutils/write-tasks-files "graphite" "install" "wheeee")
            expected-response '({"name" "apache::announce"
                                 "environment" [{"name" "production"
                                                "code_id" nil}]}
                                {"name" "graphite::install"
                                 "environment" [{"name" "production"
                                                "code_id" nil}]})
            response (get-all-tasks "production")]
        (testing "a successful status code is returned"
          (is (= 200 (:status response))
              (str
                "unexpected status code for response, response: "
                (ks/pprint-to-string response))))
        (testing "the expected response body is returned"
          (is (= (sort-tasks expected-response)
                 (sort-tasks (parse-response response)))))))))
