(ns puppetlabs.services.legacy-routes.legacy-routes-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.services.master.master-service :as master-service]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.request-handler.request-handler-service :as handler]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as webserver]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting]
            [puppetlabs.services.config.puppet-server-config-service :as ps-config]
            [puppetlabs.services.legacy-routes.legacy-routes-service :as legacy-routes]
            [puppetlabs.services.puppet-admin.puppet-admin-service :as admin]
            [puppetlabs.services.ca.certificate-authority-disabled-service :as disabled-ca]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/legacy_routes/legacy_routes_test")

(use-fixtures
  :once
  schema-test/validate-schemas
  (jruby-testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

(defn http-get [path]
  (http-client/get
    (str "https://localhost:8140" path)
    bootstrap/request-options))

; REMOVED for SERVER-768

(deftest ^:integration old-master-route-config
  (testing "The old map-style route configuration map still works."
    (bootstrap/with-puppetserver-running app
      {:web-router-service {::master-service/master-service {:master-routes "/puppet"}}}
      (is (= 200 (:status (http-get "/puppet/v3/node/localhost?environment=production"))))))

  (testing "An exception is thrown if an improper master service route is found."
    (logutils/with-test-logging
      (is (thrown-with-msg?
            IllegalArgumentException
            #"Route not found for service .*master-service"
            (bootstrap/with-puppetserver-running app
                                                 {:web-router-service {::master-service/master-service {:foo "/bar"}}}
                                                 (is (= 200 (:status (http-get "/puppet/v3/node/localhost?environment=production"))))))))))

(deftest ^:integration legacy-ca-routes-disabled
  (testing "The legacy CA routes are on longer forwarded"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running-with-services
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
         disabled-ca/certificate-authority-disabled-service]
        {}

        (is (= 404 (:status (http-get "/production/certificate_statuses/all")))
            (str "A 404 was not returned, indicating that the legacy CA routes "
                 "are still being forwarded to the core CA functions."))

        (is (not (= 200 (:status (http-get "/production/certificate_statuses/all"))))
            (str "A 200 was returned from a request made to a legacy CA endpoint "
                 "indicating the disabled CA service was not detected."))))))
