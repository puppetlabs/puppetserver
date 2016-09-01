(ns puppetlabs.services.legacy-routes.legacy-routes-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.master.master-service :as master-service]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.request-handler.request-handler-service :as handler]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as jruby-utils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as webserver]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting]
            [puppetlabs.services.config.puppet-server-config-service :as ps-config]
            [puppetlabs.services.legacy-routes.legacy-routes-service :as legacy-routes]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as tk-scheduler]
            [puppetlabs.services.puppet-admin.puppet-admin-service :as admin]
            [puppetlabs.services.ca.certificate-authority-disabled-service :as disabled-ca]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :as authorization]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
            [puppetlabs.puppetserver.testutils :as testutils :refer [http-get]]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/legacy_routes/legacy_routes_test")

(use-fixtures
  :once
  schema-test/validate-schemas
  (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

(deftest ^:integration legacy-routes
  (testing "The legacy web routing service properly handles old routes."
    (bootstrap/with-puppetserver-running app
      {}
      (is (= 200 (:status (http-get "/v2.0/environments"))))
      (is (= 200 (:status (http-get "/production/node/localhost"))))
      (is (= 200 (:status (http-get "/production/certificate_statuses/all")))))))

(deftest ^:integration old-master-route-config
  (testing "The old map-style route configuration map still works."
    (bootstrap/with-puppetserver-running app
      {:web-router-service
       {:puppetlabs.services.master.master-service/master-service
        {:master-routes "/puppet"
         :invalid-in-puppet-4 "/"}}}
      (is (= 200 (:status (http-get "/puppet/v3/node/localhost?environment=production"))))))

  (testing "The new map-style multi-server route configuration map still works."
    ;; For a multi-server config, we need to remove the existing webserver
    ;; config from the sample config.  Since `with-puppetserver-running` just does
    ;; a deep merge, there's no clean way to do this, so we just load the
    ;; config ourselves to give us more control to modify it.
    (let [default-config (bootstrap/load-dev-config-with-overrides {})
          webserver-config (:webserver default-config)]
      ;; use `-with-config` variant, so that we can pass in the entire config map
      (bootstrap/with-puppetserver-running-with-config
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
       (is (= 200 (:status (http-get "/puppet/v3/node/localhost?environment=production")))))))

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
         jruby-utils/jruby-pool-manager-service
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
         tk-scheduler/scheduler-service]
        {}

        (is (= 404 (:status (http-get "/production/certificate_statuses/all")))
            (str "A 404 was not returned, indicating that the legacy CA routes "
                 "are still being forwarded to the core CA functions."))

        (is (not (= 200 (:status (http-get "/production/certificate_statuses/all"))))
            (str "A 200 was returned from a request made to a legacy CA endpoint "
                 "indicating the disabled CA service was not detected."))))))
