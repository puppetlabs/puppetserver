(ns puppetlabs.services.legacy-routes.legacy-routes-auth-conf-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/legacy_routes/legacy_routes_auth_conf_test")

(use-fixtures
  :once
  schema-test/validate-schemas
  (jruby-testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

(defn http-get [path]
  (http-client/get
    (str "https://localhost:8140" path)
    bootstrap/request-options))

(deftest ^:integration legacy-routes-auth-conf
  (testing "The legacy web routing with puppet 4 version custom auth.conf"
    (testing "when localhost has access to the resource named `private`"
      (bootstrap/with-puppetserver-running app {}
        (logutils/with-test-logging
          (is (= 200 (:status (http-get "/production/node/public"))))
          (is (= 403 (:status (http-get "/production/node/private"))))
          (is (= 200 (:status (http-get "/production/catalog/public"))))
          (is (= 403 (:status (http-get "/production/catalog/private")))))))))
