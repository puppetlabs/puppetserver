(ns puppetlabs.services.legacy-routes.legacy-routes-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.legacy-routes.legacy-routes-service :as legacy-routes-service]
            [puppetlabs.services.master.master-service :as master-service]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]))

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

(deftest ^:integration legacy-routes
  (testing "The legacy web routing service properly handles old routes."
    (bootstrap/with-puppetserver-running app
      {:certificate-authority {:certificate-status {:authorization-required false}}}
      (is (= 200 (:status (http-get "/v2.0/environments"))))
      (is (= 200 (:status (http-get "/production/node/localhost"))))
      (is (= 200 (:status (http-get "/production/certificate_statuses/all")))))))

