(ns puppetlabs.services.jruby.jruby-pool-int-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/jruby_pool_int_test")

(use-fixtures :once
              schema-test/validate-schemas
              (jruby-testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def ca-cert
  (bootstrap/pem-file "certs" "ca.pem"))

(def localhost-cert
  (bootstrap/pem-file "certs" "localhost.pem"))

(def localhost-key
  (bootstrap/pem-file "private_keys" "localhost.pem"))

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-flush-jruby-pool-test
  (testing "Flushing the pool results in all new JRuby instances"
    (bootstrap/with-puppetserver-running
      app
      {:puppet-admin {:client-whitelist ["localhost"]}
       :jruby-puppet {:max-active-instances 4}}
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        (jruby-testutils/reduce-over-jrubies! pool-context 4 #(format "InstanceID = %s" %))
        (is (= #{0 1 2 3}
               (-> (jruby-testutils/reduce-over-jrubies! pool-context 4 (constantly "InstanceID"))
                   set)))
        (let [response (http-client/delete
                         "https://localhost:8140/puppet-admin-api/v1/jruby-pool"
                         ssl-request-options)]
          (is (= 204 (:status response))))
        ; wait until the flush is complete
        (await (:pool-agent pool-context))
        (is (every? true?
                    (jruby-testutils/reduce-over-jrubies!
                      pool-context
                      4
                      (constantly
                        "begin; InstanceID; false; rescue NameError; true; end"))))))))
