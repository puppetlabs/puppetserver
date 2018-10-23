(ns puppetlabs.services.legacy-routes.legacy-routes-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.master.master-service :as master-service]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.ca.certificate-authority-disabled-service :as disabled-ca]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.testutils :as testutils :refer [http-get]]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [cheshire.core :as cheshire]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-service]
            [cheshire.core :as json]
            [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :as puppet-profiler-core]
            [puppetlabs.services.protocols.puppet-profiler :as profiler-protocol])
  (:import (com.puppetlabs.puppetserver JRubyPuppetResponse)))

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(def test-resources-dir
  "./dev-resources/puppetlabs/services/legacy_routes/legacy_routes_test")

(use-fixtures
  :once
  schema-test/validate-schemas
  (fn [f]
    (testutils/with-config-dirs
     {(fs/file test-resources-dir "confdir")
      jruby-testutils/conf-dir
      (fs/file test-resources-dir "codedir")
      jruby-testutils/code-dir}
     (f))))

(deftest ^:integration legacy-routes-http-metrics
  (bootstrap/with-puppetserver-running
   app
   {:jruby-puppet {:gem-path gem-path}}

   (testing "Requests made to legacy endpoints are routed to new endpoints"
     (let [env-response (http-get "/v2.0/environments")
           env-response-body (-> env-response :body json/parse-string)]
       (is (= 200 (:status env-response)))
       ;; Assert that some of the content looks like it came from the
       ;; environments endpoint
       (is (not (nil? (get env-response-body "search_paths"))))
       (is (not (nil? (get env-response-body "environments")))))
     (let [node-response (logutils/with-test-logging
                          (http-get "/production/node/localhost"))
           node-response-body (-> node-response :body json/parse-string)]
       (is (= 200 (:status node-response)))
       ;; Assert that some of the content looks like it came from the
       ;; node endpoint
       (is (= "localhost" (get node-response-body "name")))
       (is (= "production" (get node-response-body "environment"))))
     (let [catalog-response (logutils/with-test-logging
                             (http-get "/production/catalog/localhost"))
           catalog-response-body (-> catalog-response :body json/parse-string)]
       (is (= 200 (:status catalog-response)))
       ;; Assert that some of the content looks like it came from the
       ;; catalog endpoint
       (is (testutils/catalog-contains? catalog-response-body
                                        "Class"
                                        "Foo"))

       (is (= "localhost" (get catalog-response-body "name")))
       (is (= "production" (get catalog-response-body "environment"))))
     (let [cert-status-response (http-get "/production/certificate_status/localhost")
           cert-status-body (-> cert-status-response :body json/parse-string)]
       (is (= 200 (:status cert-status-response)))
       ;; Assert that some of the content looks like it came from the
       ;; certificate_status endpoint
       (is (= "localhost" (get cert-status-body "name")))
       (is (= "signed" (get cert-status-body "state")))))

   ;; Add in some metrics tests for the requests just made above.

   ;; Seed metrics with an extra request which doesn't match any of the
   ;; pre-defined routes that puppetserver registers.
   (is (= 404 (:status (http-get
                        "/puppet/funky/town"))))
   (let [master-service (tk-app/get-service app :MasterService)
         svc-context (tk-services/service-context master-service)
         http-metrics (:http-metrics svc-context)
         profiler-service (tk-app/get-service app :PuppetProfilerService)
         puppet-profiler (profiler-protocol/get-profiler profiler-service)]

     (testing "Metrics are captured for requests to legacy routes"
       (is (= 4 (-> http-metrics :total-timer .getCount)))
       (is (= 1 (-> http-metrics :route-timers :other .getCount)))
       (is (= 1 (-> http-metrics :route-timers
                    (get "puppet-v3-environments")
                    .getCount)))
       (is (= 1 (-> http-metrics :route-timers
                    (get "puppet-v3-node-/*/")
                    .getCount)))
       (is (= 1 (-> http-metrics :route-timers
                    (get "puppet-v3-catalog-/*/")
                    .getCount)))
       (is (= 0 (-> http-metrics :route-timers
                    (get "puppet-v3-report-/*/")
                    .getCount))))

     (testing (str "Catalog compilation increments the catalog counter and "
                   "adds timing data")
       (let [catalog-metrics (map puppet-profiler-core/catalog-metric
                                  (.getCatalogTimers puppet-profiler))
             compile-metric (->> catalog-metrics
                                 (filter #(= "compile" (:metric %))))]
         (is (= 1 (count compile-metric)))
         (let [metric (first compile-metric)]
           (is (= 1 (metric :count)))
           (is (= (metric :aggregate) (metric :mean)))))))

   (let [resp (http-get "/status/v1/services?level=debug")]
     (is (= 200 (:status resp)))
     (let [status (json/parse-string (:body resp) true)]

       (is (= #{:ca :jruby-metrics :master :puppet-profiler :status-service}
              (set (keys status))))

       (is (= 1 (get-in status [:jruby-metrics :service_status_version])))
       (is (= "running" (get-in status [:jruby-metrics :state])))
       (is (nil? (schema/check jruby-metrics-core/JRubyMetricsStatusV1
                               (get-in status [:jruby-metrics :status]))))

       (is (= jruby-metrics-core/jruby-pool-lock-not-in-use
              (get-in status [:jruby-metrics :status :experimental
                              :jruby-pool-lock-status :current-state])))

       (is (= 1 (get-in status [:master :service_status_version])))
       (is (= "running" (get-in status [:master :state])))
       (is (nil? (schema/check master-core/MasterStatusV1
                               (get-in status [:master :status]))))
       (testing (str "HTTP metrics in status endpoint are sorted in order of "
                     "aggregate amount of time spent")
         (let [hit-routes #{"total"
                            "puppet-v3-environments"
                            "puppet-v3-node-/*/"
                            "puppet-v3-catalog-/*/"
                            "other"}
               http-metrics (get-in status [:master
                                            :status
                                            :experimental
                                            :http-metrics])]
           (testing (str "'total' should come first since it is the sum of "
                         "the other endpoints")
             (is (= "total" (:route-id (first http-metrics)))))
           (testing (str "The other three routes that actually received "
                         "requests should come next")
             (is (= #{"puppet-v3-environments"
                      "puppet-v3-node-/*/"
                      "puppet-v3-catalog-/*/"}
                    (set (map :route-id (rest (take 4 http-metrics)))))))
           (testing "The aggregate times should be in descending order"
             (let [aggregate-times (map :aggregate http-metrics)]
               (= aggregate-times (reverse (sort aggregate-times)))))
           (testing (str "The counts should be accurate for the endpoints "
                         "that we hit")
             (let [find-route (fn [route-metrics route-id]
                                (first (filter #(= (:route-id %) route-id) route-metrics)))]
               (is (= 4 (:count (find-route http-metrics "total"))))
               (is (= 1 (:count (find-route http-metrics "puppet-v3-environments"))))
               (is (= 1 (:count (find-route http-metrics "puppet-v3-node-/*/"))))
               (is (= 1 (:count (find-route http-metrics "puppet-v3-catalog-/*/"))))
               (is (= 1 (:count (find-route http-metrics "other"))))))
           (testing "The counts should be zero for endpoints that we didn't hit"
             (is (every? #(= 0 %) (map :count
                                       (filter
                                        #(not (hit-routes (:route-id %)))
                                        http-metrics)))))))

       (is (= 1 (get-in status [:puppet-profiler :service_status_version])))
       (is (= "running" (get-in status [:puppet-profiler :state])))
       (is (nil? (schema/check puppet-profiler-core/PuppetProfilerStatusV1
                               (get-in status [:puppet-profiler :status]))))
       (let [function-metrics (get-in status
                                      [:puppet-profiler
                                       :status
                                       :experimental
                                       :function-metrics])
             function-names (set (mapv :function function-metrics))]
         (is (contains? function-names "digest"))
         (is (contains? function-names "include")))

       (let [resource-metrics (get-in status [:puppet-profiler
                                              :status
                                              :experimental
                                              :resource-metrics])
             resource-names (set (mapv :resource resource-metrics))]
         (is (contains? resource-names "Class[Foo]"))
         (is (contains? resource-names "Class[Foo::Params]"))
         (is (contains? resource-names "Class[Foo::Bar]")))))))

(deftest ^:integration legacy-routes-jruby-metrics
  (testing "JRuby metrics computed via use of the legacy routes service actions are correct"
    (bootstrap/with-puppetserver-running
     app
     {:jruby-puppet {:gem-path gem-path}}

     (let [jruby-metrics-service (tk-app/get-service app :JRubyMetricsService)
           svc-context (tk-services/service-context jruby-metrics-service)
           jruby-metrics (:metrics svc-context)
           jruby-service (tk-app/get-service app :JRubyPuppetService)
           time-before-first-borrow (System/currentTimeMillis)]
       ;; Use with-jruby-puppet to borrow the lone jruby out of the pool,
       ;; which should cause a subsequent catalog request to block on a borrow
       ;; request
       (jruby-service/with-jruby-puppet
        _
        jruby-service
        :legacy-routes-test
        (let [time-before-second-borrow (System/currentTimeMillis)]
          (future
           (logutils/with-test-logging
            (http-get "/production/catalog/localhost")))
          ;; Wait up to 10 seconds for the catalog request to get to the
          ;; point where it is in the jruby borrow queue.
          (while (and
                  (< (- (System/currentTimeMillis) time-before-second-borrow)
                     10000)
                  (nil? (-> jruby-metrics
                            :requested-instances
                            deref
                            first
                            (get-in [:reason :request :uri]))))
            (Thread/yield))
          (let [resp (http-get "/status/v1/services/jruby-metrics?level=debug")]
            (is (= 200 (:status resp)))
            (let [status (json/parse-string (:body resp) true)]
              (is (= 1 (:service_status_version status)))
              (is (= "running" (:state status)))

              (testing "Info for borrowed instance is correct"
                (let [borrowed-instances (get-in status [:status
                                                         :experimental
                                                         :metrics
                                                         :borrowed-instances])
                      borrowed-instance (first borrowed-instances)]
                  (is (= 1 (count borrowed-instances)))
                  (is (= "legacy-routes-test" (:reason borrowed-instance)))
                  (is (>= (:time borrowed-instance) time-before-first-borrow))
                  (is (> (:duration-millis borrowed-instance) 0))
                  (is (>= (System/currentTimeMillis)
                          (+ (:duration-millis borrowed-instance)
                             (:time borrowed-instance))))))

              (testing "Info for requested instance is correct"
                (let [requested-instances (get-in status [:status
                                                          :experimental
                                                          :metrics
                                                          :requested-instances])
                      requested-instance (first requested-instances)]
                  (is (= 1 (count requested-instances)))
                  (is (= {:request
                          {:request-method "get"
                           :route-id "puppet-v3-catalog-/*/"
                           :uri "/puppet/v3/catalog/localhost"}}
                         (:reason requested-instance)))
                  (is (>= (:time requested-instance) time-before-second-borrow))
                  (is (> (:duration-millis requested-instance) 0))
                  (is (>= (System/currentTimeMillis)
                          (+ (:duration-millis requested-instance)
                             (:time requested-instance))))))))))))))

(deftest ^:integration old-master-route-config
  (testing "The old map-style route configuration map still works."
    (bootstrap/with-puppetserver-running-with-mock-jruby-puppet-fn
     "JRuby mocking is safe here because we're just interested in validating
     that a request would be properly routed down to the Ruby layer even with
     the alternate :master-routes / :invalid-in-puppet-4 keys in the route
     configuration.  Note that :invalid-in-puppet-4 is essentially ignored
     in Puppet Server 2.1+ but this test ensures that its presence in
     configuration doesn't adversely affect request routing."
     app
     {:web-router-service
      {:puppetlabs.services.master.master-service/master-service
       {:master-routes "/puppet"
        :invalid-in-puppet-4 "/"}}}
     (partial jruby-testutils/create-mock-jruby-puppet
              (fn [request]
                (JRubyPuppetResponse.
                 (Integer. 200)
                 (str "request routed to: " (get request "uri"))
                 "text/plain"
                 "1.2.3")))
     (let [node-response (http-get "/puppet/v3/node/localhost?environment=production")]
       (is (= 200 (:status node-response)))
       (is (= "request routed to: /puppet/v3/node/localhost" (:body node-response))))))

  (testing "The new map-style multi-server route configuration map still works."
    ;; For a multi-server config, we need to remove the existing webserver
    ;; config from the sample config.  Since `with-puppetserver-running` just does
    ;; a deep merge, there's no clean way to do this, so we just load the
    ;; config ourselves to give us more control to modify it.
    (let [default-config (bootstrap/load-dev-config-with-overrides {})
          webserver-config (:webserver default-config)]
      ;; use `-with-config` variant, so that we can pass in the entire config map
      (bootstrap/with-puppetserver-running-with-mock-jruby-puppet-fn
       "JRuby mocking is safe here because we're just interested in validating
       that a request would be properly routed down to the Ruby layer for a
       multiple webserver configuration."
       app
       (-> default-config
           ;; remove the 'root' webserver config, which wouldn't exist in a
           ;; multi-server config
           (dissoc :webserver)
           ;; add the webserver config back in with an id of `:puppetserver`
           (assoc :webserver {:puppetserver
                              (assoc webserver-config
                                :default-server true)})
           (ks/deep-merge
            {:web-router-service
             ;; set the master service to use the map-based multi-server-style config
             {:puppetlabs.services.master.master-service/master-service
              {:route "/puppet"
               :server "puppetserver"}}}))
       (partial jruby-testutils/create-mock-jruby-puppet
                (fn [request]
                  (JRubyPuppetResponse.
                   (Integer. 200)
                   (str "request routed to: " (get request "uri"))
                   "text/plain"
                   "1.2.3")))
       (let [node-response (http-get "/puppet/v3/node/localhost?environment=production")]
         (is (= 200 (:status node-response)))
         (is (= "request routed to: /puppet/v3/node/localhost" (:body node-response)))))))

  (testing "An exception is thrown if an improper master service route is found."
    (logutils/with-test-logging
      (is (thrown-with-msg?
            IllegalArgumentException
            #"Route not found for service .*master-service"
            (bootstrap/with-puppetserver-running-with-mock-jrubies
             "JRuby mocking is safe here because the test doesn't actually do
             anything with JRuby instances - just validates that an error
             occurs due to an invalid web routing configuration at startup."
             app
             {:web-router-service {::master-service/master-service {:foo "/bar"}}}))))))

(deftest ^:integration legacy-ca-routes-disabled
  (testing (str "(SERVER-759) The legacy CA routes are not forwarded when the "
                "disabled CA is configured")
    ;; Startup server once with real CA service so that SSL certs/keys/etc.
    ;; are generated properly before we start again with the disabled CA
    ;; service bootstrapped.  When starting up with disabled CA service,
    ;; the certs/keys/etc. are assumed to already be in place at startup.  The
    ;; server would fail to start on being unable to find any of these files.
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "Mocking JRubies because don't need real ones just to have SSL files created"
     _
     {:jruby-puppet {:gem-path gem-path}}
     ;; Sanity check to see that the server was started up the first time
     (is true))
    (bootstrap/with-puppetserver-running-with-services-and-mock-jrubies
     "Mocking JRubies because CA endpoints are pure clojure"
     app
     (->> bootstrap/services-from-dev-bootstrap
          (remove #(= :CaService (tk-services/service-def-id %)))
          (cons disabled-ca/certificate-authority-disabled-service))
     {:jruby-puppet {:gem-path gem-path}}

     (is (= 404 (:status (http-get "/production/certificate_statuses/all")))
         (str "A 404 was not returned, indicating that the legacy CA routes "
              "are still being forwarded to the core CA functions."))

     (is (not (= 200 (:status (http-get "/production/certificate_statuses/all"))))
         (str "A 200 was returned from a request made to a legacy CA endpoint "
              "indicating the disabled CA service was not detected.")))))
