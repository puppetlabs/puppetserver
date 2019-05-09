(ns puppetlabs.services.master.environment-transports-int-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap-testutils]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [cheshire.core :as json]
            [me.raynes.fs :as fs]
            [clojure.tools.logging :as log]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils])
  (:import (com.puppetlabs.puppetserver JRubyPuppetResponse JRubyPuppet)
           (java.util HashMap ArrayList)))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/environment_transports_int_test")

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])


(def mock-transport
  {:name "foo_transport"
    :desc "A mock transport schema"
    :connection_info {
      :host {
        :type "String"
        :desc "The host"}}})

(def mock-response
  {:name "production"
    :transports [mock-transport]})

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

(defn get-env-transports
  ([env-name]
   (get-env-transports env-name nil))
  ([env-name if-none-match]
   (let [opts (if if-none-match
                {:headers {"If-None-Match" if-none-match}})]
     (try
       (http-client/get
        (str "https://localhost:8140/puppet/v3/"
             "environment_transports?"
             "environment="
             env-name)
        (merge
         testutils/ssl-request-options
         {:as :text}
         opts))
       (catch Exception e
         (throw (Exception. "environment_transports http get failed" e)))))))

(defn purge-all-env-caches
  []
  (http-client/delete
   "https://localhost:8140/puppet-admin-api/v1/environment-cache"
   testutils/ssl-request-options))

(defn purge-env-cache
  [env]
  (http-client/delete
   (str "https://localhost:8140/puppet-admin-api/v1/environment-cache?"
        "environment="
        env)
   testutils/ssl-request-options))

(defn response-etag
  [request]
  (get-in request [:headers "etag"]))

(defn etag-with-gzip-suffix
  [etag]
  (if (.endsWith etag "--gzip")
    etag
    (str etag "--gzip")))

(defn etag-without-gzip-suffix
  [etag]
  (str/replace etag #"--gzip$" ""))

(defn response->map
  [response]
  (-> response :body (json/parse-string true)))


(deftest ^:integration environment-transports-integration-cache-disabled-test
  (testing "when environment cache is disabled for a transport request"
    (bootstrap/with-puppetserver-running-with-config
     app
     (-> {:jruby-puppet {:gem-path gem-path
                         :max-active-instances 1}}
         (bootstrap/load-dev-config-with-overrides)
         (ks/dissoc-in [:jruby-puppet
                        :environment-class-cache-enabled]))
     (let [foo-file (testutils/write-foo-pp-file
                     "some kind of test data")
           expected-response mock-response
           response (get-env-transports "production")]
       (testing "a successful status code is returned"
         (is (= 200 (:status response))
             (str
              "unexpected status code for response, response: "
              (ks/pprint-to-string response))))
       (testing "no etag is returned"
         (is (false? (contains? (:headers response) "etag"))))
       (testing "the expected response body is returned"
         (is (= expected-response
                (response->map response))))))))


(deftest ^:integration environment-transports-integration-cache-enabled-test1
  (bootstrap/with-puppetserver-running app
   {:jruby-puppet {:gem-path gem-path
                   :max-active-instances 1
                   :environment-class-cache-enabled true}}
   (let [foo-file (str "tbd test data")
         expected-initial-response (json/encode mock-response)
         initial-response (get-env-transports "production")
         initial-etag (response-etag initial-response)
         initial-etag-with-gzip-suffix (etag-with-gzip-suffix initial-etag)
         initial-etag-without-gzip-suffix (etag-without-gzip-suffix initial-etag)]
     (testing "environment transport cache invalidation for one environment"
       ;; This test is about ensuring that when the environment-cache
       ;; endpoint is hit for a single environment that only that the
       ;; environment class info cached for that environment - but not the
       ;; info cached for other environments - is invalidated, meaning that
       ;; the next request for class info for that environment will get fresh
       ;; data.
       ;;
       ;; The test has the following basic steps:
       ;;
       ;; 1) Purge the current environment files on disk and hit the
       ;;    environment-cache endpoint to flush the cache for all
       ;;    environments, basically to ensure nothing is left around from
       ;;    other tests.
       ;; 2) Populate code for the 'test' and 'production' environments and
       ;;    see that environment_transports queries return the right info for
       ;;    them.
       ;; 3) Do one more environment_transports query for each environment,
       ;;    using the etag from the initial queries, to ensure that a 304
       ;;    (Not Modified) is returned.
       ;; 4) Change code on disk for both the 'test' and 'production'
       ;;    environments.
       ;; 5) Hit the environment-cache endpoint for the 'production'
       ;;    environment (but not for the 'test' environment).
       ;; 6) Do two more environment_transports queries for the 'test' and
       ;;    'production' environments using the etags from the initial
       ;;    queries.  Confirm that the information reflects the latest data on
       ;;    disk for the 'production' environment but still reflects the old
       ;;    data for the 'test' environment - expected, in this case, because
       ;;    the 'test' environment was not flushed.
       (purge-env-dir)
       (purge-all-env-caches)
       (let [prod-file (str "test data")
             test-file (str "test data")
             production-response-initial (get-env-transports "production")
             production-etag-initial (response-etag production-response-initial)
             production-etag-initial-without-gzip-suffix (etag-without-gzip-suffix production-etag-initial)
             test-response-initial (get-env-transports "test")
             test-etag-initial (response-etag test-response-initial)
             test-etag-initial-without-gzip-suffix (etag-without-gzip-suffix
                                                     (response-etag test-response-initial))]
         (is (= 200 (:status production-response-initial))
             (str
              "unexpected status code for initial production response"
              "response: "
              (ks/pprint-to-string production-response-initial)))
         (is (not (nil? production-etag-initial))
             "no etag returned for production response")
         (is (= mock-response
                (response->map production-response-initial))
             "unexpected body for production response")
         (comment
           "We are currently only returning mock data for prod"
           (is (= 200 (:status test-response-initial))
               (str
                "unexpected status code for initial test response"
                "response: "
                (ks/pprint-to-string test-response-initial)))
           (is (not (nil? test-etag-initial))
               "no etag returned for test response")
           (is (= mock-response
                  (response->map test-response-initial))
               "unexpected body for test response"))

         (comment
           (write-some-test-data "test data")
           (write-some-test-data "test data")

           "The following tests verify even though we just wrote new data
           to disk the response is still honoring the cache since it has
           yet to be purged.

           Since the writing of data is not done, these tests basically
           repeat the above section of tests. They are left here for a
           future commit when we write realistic transports to disk"

           (let [production-response-before-flush (get-env-transports
                                                   "production"
                                                   production-etag-initial)
                 test-response-before-flush (get-env-transports
                                             "test"
                                             test-etag-initial)]
             (is (= 304 (:status production-response-before-flush))
                 (str
                  "unexpected status code for prod response after code change "
                  "but before flush"))
             (is (= production-etag-initial-without-gzip-suffix (response-etag
                                                                  production-response-before-flush))
                 "unexpected etag change when no production environment change")
             (is (empty? (:body production-response-before-flush))
                 "unexpected body for production response")
             (is (= 304 (:status test-response-before-flush))
                 (str
                  "unexpected status code for test response after code change "
                  "but before flush"))
             (is (= test-etag-initial-without-gzip-suffix (response-etag
                                                            test-response-before-flush))
                 "unexpected etag change when no test environment change")
             (is (empty? (:body test-response-before-flush))
                 "unexpected body for test response")))

           (comment
             "The following tests require the code that was written _prior_
             to the previous set of tests. The following tests validate
             that, once we've purged the cache new data based on those
             written files will be returned first and then cached."
             (purge-env-cache "production")
             (let [production-response-after-prod-flush (get-env-transports
                                                         "production"
                                                         production-etag-initial)
                   production-etag-after-prod-flush (response-etag
                                                     production-response-after-prod-flush)
                   test-response-after-prod-flush (get-env-transports
                                                   "test"
                                                   test-etag-initial)]
               (is (= 200 (:status production-response-after-prod-flush))
                   (str
                    "unexpected status code for prod response after code change "
                    "and prod flush"))
               (is (not (nil? production-etag-after-prod-flush))
                   "no etag returned for production response")
               (is (not= production-etag-initial
                         production-etag-after-prod-flush)
                   (str
                    "etag unexpectedly stayed the same even though "
                    "the production environment changed"))
               (is (= mock-response
                      (response->map
                       production-response-after-prod-flush))
                   "unexpected body for production response")
               (is (= 304 (:status test-response-after-prod-flush))
               (is (= test-etag-initial-without-gzip-suffix (response-etag
                                                              test-response-after-prod-flush))
                   "unexpected etag change when test environment not invalidated")
               (is (empty? (:body test-response-after-prod-flush))
                   "unexpected body for test response")))))))))


(deftest ^:integration environment-transports-integration-cache-enabled-test2
  (bootstrap/with-puppetserver-running app
   {:jruby-puppet {:gem-path gem-path
                   :max-active-instances 1
                   :environment-class-cache-enabled true}}
   (let [foo-file (str "tbd test data")
         expected-initial-response mock-response
         initial-response (get-env-transports "production")
         initial-etag (response-etag initial-response)
         initial-etag-with-gzip-suffix (etag-with-gzip-suffix initial-etag)
         initial-etag-without-gzip-suffix (etag-without-gzip-suffix initial-etag)]
     (testing "initial fetch of environment_transports info is good"
       (is (= 200 (:status initial-response))
           (str
            "unexpected status code for initial response, response: "
            (ks/pprint-to-string initial-response)))
       (is (not (nil? initial-etag))
           "no etag found for initial response")
       (is (= expected-initial-response
              (response->map initial-response))
           "unexpected body for initial response"))
     (testing "etag not updated when code has not changed"
       (let [response (get-env-transports "production")]
         (is (= 200 (:status response))
             "unexpected status code for response following no code change")
         (is (= initial-etag (response-etag response))
             "etag changed even though code did not")
         (is (= expected-initial-response
                (response->map response))
             "unexpected body for response")))
     (testing (str "HTTP 304 (not modified) returned when request "
                   "roundtrips last etag and code has not changed")
       (let [response (get-env-transports "production" initial-etag)]
         (is (= 304 (:status response))
             (str
              "unexpected status code for response for no code change and "
              "original etag roundtripped"))
         (is (= initial-etag-without-gzip-suffix (response-etag response))
             "etag changed even though code did not")
         (is (empty? (:body response))
             "unexpected body for response")))
     (testing (str "SERVER-1153 - HTTP 304 (not modified) returned when "
                   "request roundtrips last etag with '--gzip' suffix and "
                   "code has not changed")
       (let [response (get-env-transports "production"
                                       initial-etag-with-gzip-suffix)]
         (is (= 304 (:status response))
             (str
              "unexpected status code for response for no code change and "
              "etag with '--gzip' suffix roundtripped"))
         (is (= initial-etag-without-gzip-suffix (response-etag response))
             "etag changed even though code did not")
         (is (empty? (:body response))
             "unexpected body for response")))
     (comment
       "Not valid until we write new data out"
       (testing (str "environment_transport fetch without if-none-match "
                     "header includes latest info after code update")
         (str "test data")
         (str "test data")
         (let [baz-file (str "test data")
               borked-file (str "test data")
               _ (purge-all-env-caches)
               response (get-env-transports "production")]
           (is (= 200 (:status response))
               (str
                "unexpected status code for response following code change,"
                "response: "
                (ks/pprint-to-string response)))
           (is (not= initial-etag (response-etag response))
               "etag did not change even though code did")
           (is (= mock-response
                  (response->map response))
               "unexpected body following code change")))))))


#_(deftest ^:integration environment-transports-integration-cache-enabled-test3
  (bootstrap/with-puppetserver-running app
   {:jruby-puppet {:gem-path gem-path
                   :max-active-instances 1
                   :environment-class-cache-enabled true}}
   (let [foo-file (str "tbd test data")
         expected-initial-response mock-response
         initial-response (get-env-transports "production")
         initial-etag (response-etag initial-response)
         initial-etag-with-gzip-suffix (etag-with-gzip-suffix initial-etag)
         initial-etag-without-gzip-suffix (etag-without-gzip-suffix initial-etag)]
     (testing "environment transports cache invalidation for all environments"
       ;; This test is about ensuring that when the environment-cache
       ;; endpoint is hit with no environment parameter that any previously
       ;; cached environment class info is invalidated, meaning that
       ;; the next request for class info for all environments will get fresh
       ;; data.
       ;;
       ;; To eliminate some of the redundancy between tests, this test doesn't
       ;; repeat the intermediate step of checking to see that the first two
       ;; environment queries were cached - 304 (Not Modified) returns -
       ;; between the first set of environment_transports queries and the second
       ;; set, done after the code on disk for both environments has changed.
       ;;
       ;; The test has the following basic steps:
       ;;
       ;; 1) Purge the current environment files on disk and hit the
       ;;    environment-cache endpoint to flush the cache for all
       ;;    environments, basically to ensure nothing is left around from
       ;;    other tests.
       ;; 2) Populate code for the 'test' and 'production' environments and
       ;;    see that environment_transports queries return the right info for
       ;;    them.
       ;; 3) Change code on disk for both the 'test' and 'production'
       ;;    environments.
       ;; 4) Hit the environment-cache endpoint with no environment
       ;;    parameter, expected to have the effect of flushing the cache for
       ;;    all environments.
       ;; 5) Do two more environment_transports queries for the 'test' and
       ;;    'production' environments with the corresponding etags returned
       ;;    from the first two queries.  Confirm that the information
       ;;    reflects the latest data on disk for both environments.
       (purge-env-dir)
       (purge-all-env-caches)
       (let [prod-file (str "test data")
             test-file (str "test data")
             production-response-initial (get-env-transports "production")
             production-etag-initial (response-etag production-response-initial)
             test-response-initial (get-env-transports "test")
             test-etag-initial (response-etag test-response-initial)]
         (is (= 200 (:status production-response-initial))
             (str
              "unexpected status code for initial production response"
              "response: "
              (ks/pprint-to-string production-response-initial)))
         (is (not (nil? production-etag-initial))
             "no etag returned for production response")
         (is (= mock-response
                (response->map production-response-initial))
             "unexpected body for production response")
         (is (= 200 (:status test-response-initial))
             (str
              "unexpected status code for initial test response"
              "response: "
              (ks/pprint-to-string test-response-initial)))
         (is (not (nil? test-etag-initial))
             "no etag returned for test response")
         (is (= mock-response
                (response->map test-response-initial))
             "unexpected body for test response")

         (str "test data")
         (str "test data")
         (purge-all-env-caches)

         (let [production-response-after-all-flush (get-env-transports
                                                    "production"
                                                    production-etag-initial)
               production-etag-after-all-flush (response-etag
                                                production-response-after-all-flush)]
           (is (= 200 (:status production-response-after-all-flush))
               (str
                "unexpected status code for prod response after code change "
                "and all environment flush"))
           (is (not (nil? production-etag-after-all-flush))
               "no etag returned for production response")
           (is (not= production-etag-initial production-etag-after-all-flush)
               (str
                "etag unexpectedly stayed the same even though "
                "the production environment changed"))
           (is (= mock-response
                  (response->map
                   production-response-after-all-flush))
               "unexpected body for production response"))

         (let [test-response-after-all-flush (get-env-transports
                                              "test"
                                              test-etag-initial)
               test-etag-after-all-flush (response-etag
                                          test-response-after-all-flush)]
           (is (= 200 (:status test-response-after-all-flush))
               (str
                "unexpected status code for test response after code change "
                "and all environment flush"))
           (is (not (nil? test-etag-after-all-flush))
               "no etag returned for test response")
           (is (not= test-etag-initial test-etag-after-all-flush)
               (str
                "etag unexpectedly stayed the same even though "
                "the test environment changed"))
           (is (= mock-response
                  (response->map
                   test-response-after-all-flush))
               "unexpected body for test response")))))))

(deftest ^:integration
         not-modified-returned-for-environment-transports-info-request-with-gzip-tag
  (testing (str "SERVER-1153 - when the webserver gzips the response "
                "containing environment_transports etag, the next request "
                "roundtripping that etag returns an HTTP 304 (Not Modified)")
    (let [expected-etag "abcd1234"
          body-length 200000
          jruby-service (reify jruby-protocol/JRubyPuppetService
                          (get-environment-info-tag [_ _ _]
                            expected-etag))
          app (->
               (fn [_]
                 (master-core/response-with-etag
                  (apply str (repeat body-length "a"))
                  expected-etag))
               (master-core/wrap-with-etag-check jruby-service))]
      (jetty9/with-test-webserver
       app
       port
       (let [request-url (str "http://localhost:" port)
             initial-response (http-client/get request-url {:as :text})
             response-tag (response-etag initial-response)
             response-with-tag (http-client/get
                                request-url
                                {:headers {"If-None-Match" response-tag}
                                 :as :text})]
         (is (= 200 (:status initial-response))
             "response for initial request is not 'successful'")
         (is (= (get-in initial-response [:headers "content-encoding"]) "gzip")
             "response from initial request was not gzipped")
         (is (= body-length (count (:body initial-response)))
             "unexpected response body length for initial response")
         (is (= (str expected-etag "--gzip") response-tag)
             "unexpected etag returned for initial response")
         (is (= 304 (:status response-with-tag))
             (str "request with prior etag did not return http 304 (not "
                  "modified) status code"))
         (is (empty? (:body response-with-tag))
             "unexpected body for request with prior etag")
         (is (= expected-etag (response-etag response-with-tag))
             "unexpected etag returned for request with prior etag"))))))

(defn create-jruby-instance-with-mock-transports-info
  [wait-atom transports-info-atom config]
  (let [puppet-config (jruby-testutils/mock-puppet-config-settings
                       (:jruby-puppet config))]
    (reify JRubyPuppet
      (getSetting [_ setting]
        (get puppet-config setting))
      (getClassInfoForEnvironment [_ _]
        (let [class-info
              {"/some/file"
               (doto (HashMap.)
                 (.put
                  "classes"
                  @transports-info-atom))}]
          (when-let [promises @wait-atom]
            (deliver (:wait-promise promises) true)
            @(:continue-promise promises))
          class-info))
      (getTransportInfoForEnvironment [_ _]
        (let [a (ArrayList.)
              t (walk/stringify-keys mock-transport)]
          (.add a t)
          a))
      (handleRequest [_ _]
        (JRubyPuppetResponse. 0 nil nil nil))
      (puppetVersion [_]
        "1.2.3")
      (terminate [_]
        (log/info "Terminating Master")))))

#_(deftest ^:integration transports-info-updated-after-cache-flush-during-prior-request
  (let [transports-info-atom (atom [{"name" "someclass" "params" []}])
        wait-atom (atom nil)
        config (bootstrap/load-dev-config-with-overrides
                {:jruby-puppet {:gem-path gem-path
                                :max-active-instances 1
                                :environment-class-cache-enabled true}})
        mock-jruby-fn (partial create-jruby-instance-with-mock-transports-info
                               wait-atom
                               transports-info-atom)]
    ;; This test uses a mock jruby instance function which can provide mock
    ;; data for an environment class info query and can suspend a request
    ;; long enough for the cached environment data to be invalidated.
    (tk-bootstrap-testutils/with-app-with-config
     app
     (bootstrap/services-from-dev-bootstrap-plus-mock-jruby-pool-manager-service
      config
      mock-jruby-fn)
     config
     (let [continue-promise (promise)
           wait-promise (promise)
           _ (reset! wait-atom {:continue-promise continue-promise
                                :wait-promise wait-promise})
           initial-response-future (future (get-env-transports "production"))]
       (is (true? (deref wait-promise 10000 :timed-out))
           (str "timed out waiting for get transports info call to be reached "
                "in mock jrubypuppet instance"))
       (reset! transports-info-atom [{"name" "updatedclass"
                                 "params" []}])
       (purge-env-cache "production")
       (deliver continue-promise true)
       (let [initial-response @initial-response-future
             initial-response-etag (response-etag initial-response)
             _ (reset! wait-atom nil)
             response-after-update (get-env-transports "production"
                                                    initial-response-etag)]
         (testing (str "initial request in progress while environment "
                       "cache is invalidated contains original transports info")
           (is (= 200 (:status initial-response))
               (str
                "unexpected status code for initial response"
                "response: "
                (ks/pprint-to-string initial-response)))
           (is (not (nil? initial-response-etag))
               "no etag returned for initial response")
           (is (= mock-response
                  (response->map initial-response))
               "unexpected body for initial response"))
         (testing (str "transports info updated properly for "
                       "request made after environment cache was purged "
                       "during a previous transports info request")
           (is (= 200 (:status response-after-update))
               (str
                "unexpected status code for response after update, "
                "response: "
                (ks/pprint-to-string response-after-update)))
           (is (not= initial-response-etag
                     (response-etag response-after-update))
               "unexpected etag for response after update")
           (is (= mock-response
                  (response->map response-after-update))
               "unexpected body for response after update")))))))
