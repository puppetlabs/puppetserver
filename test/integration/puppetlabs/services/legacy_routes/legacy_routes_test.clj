(ns puppetlabs.services.legacy-routes.legacy-routes-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.master.master-service :as master-service]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.request-handler.request-handler-service :as handler]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as webserver]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting]
            [puppetlabs.services.config.puppet-server-config-service :as ps-config]
            [puppetlabs.services.legacy-routes.legacy-routes-service :as legacy-routes]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as tk-scheduler]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :as metrics]
            [puppetlabs.services.puppet-admin.puppet-admin-service :as admin]
            [puppetlabs.services.ca.certificate-authority-disabled-service :as disabled-ca]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :as authorization]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
            [puppetlabs.puppetserver.testutils :as testutils :refer [http-get]]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [cheshire.core :as cheshire])
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
        [handler/request-handler-service
         jruby/jruby-puppet-pooled-service
         profiler/puppet-profiler-service
         webserver/jetty9-service
         webrouting/webrouting-service
         ps-config/puppet-server-config-service
         master-service/master-service
         legacy-routes/legacy-routes-service
         admin/puppet-admin-service
         disabled-ca/certificate-authority-disabled-service
         authorization/authorization-service
         vcs/versioned-code-service
         tk-scheduler/scheduler-service
         metrics/metrics-service]
        {}

        (is (= 404 (:status (http-get "/production/certificate_statuses/all")))
            (str "A 404 was not returned, indicating that the legacy CA routes "
                 "are still being forwarded to the core CA functions."))

        (is (not (= 200 (:status (http-get "/production/certificate_statuses/all"))))
            (str "A 200 was returned from a request made to a legacy CA endpoint "
                 "indicating the disabled CA service was not detected."))))))
