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
            [cheshire.core :as json])
  (:import (com.puppetlabs.puppetserver JRubyPuppetResponse)))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/legacy_routes/legacy_routes_test")

(use-fixtures
  :once
  schema-test/validate-schemas
  (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

(deftest ^:integration legacy-routes
  (testing "The legacy web routing service properly handles old routes."
    (bootstrap/with-puppetserver-running-with-mock-jruby-puppet-fn
     "JRuby mocking is safe ,here because we're only interested in validating
     that the request is routed from a legacy to a new endpoint and the
     translation is expected to occur at the Clojure layer, not in Ruby."
     app
     {}
     (partial jruby-testutils/create-mock-jruby-puppet
              (fn [request]
                (JRubyPuppetResponse.
                 (Integer. 200)
                 (str "request routed to: " (get request "uri"))
                 "text/plain"
                 "1.2.3")))
     (let [env-response (http-get "/v2.0/environments")]
       (is (= 200 (:status env-response)))
       (is (= "request routed to: /puppet/v3/environments" (:body env-response))))
     (let [node-response (http-get "/production/node/localhost")]
       (is (= 200 (:status node-response)))
       (is (= "request routed to: /puppet/v3/node/localhost" (:body node-response))))
     (let [cert-status-response (http-get "/production/certificate_status/localhost")
           cert-status-body (-> cert-status-response :body cheshire/parse-string)]
       (is (= 200 (:status cert-status-response)))
       ;; Assert that some of the content looks like it came from the
       ;; certificate_status endpoint
       (is (= "localhost" (get cert-status-body "name")))
       (is (= "signed" (get cert-status-body "state")))))))

(deftest ^:integration legacy-routes-metrics
  (bootstrap/with-puppetserver-running
   app
   {}
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
                           (:time requested-instance)))))))))))))

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
       (is (= "request routed to: /puppet/v3/node/localhost")))))

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
         (is (= "request routed to: /puppet/v3/node/localhost"))))))

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
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running-with-services-and-mock-jrubies
       "Mocking JRubies because CA endpoints are pure clojure"
        app
       (->> bootstrap/services-from-dev-bootstrap
            (remove #(= :CaService (tk-services/service-def-id %)))
            (cons disabled-ca/certificate-authority-disabled-service))
        {}

        (is (= 404 (:status (http-get "/production/certificate_statuses/all")))
            (str "A 404 was not returned, indicating that the legacy CA routes "
                 "are still being forwarded to the core CA functions."))

        (is (not (= 200 (:status (http-get "/production/certificate_statuses/all"))))
            (str "A 200 was returned from a request made to a legacy CA endpoint "
                 "indicating the disabled CA service was not detected."))))))
