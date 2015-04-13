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

(def script-to-check-if-constant-is-defined
  "begin; InstanceID; true; rescue NameError; false; end")

(defn add-watch-for-flush-complete
  [pool-context]
  (let [flush-complete (promise)]
    (add-watch (:pool-agent pool-context) :flush-callback
               (fn [k a old-state new-state]
                 (when (= k :flush-callback)
                   (remove-watch a :flush-callback)
                   (deliver flush-complete true))))
    flush-complete))

(defn set-constants-and-verify
  [pool-context]
  ;; here we set a constant called 'InstanceId' in each instance
  (jruby-testutils/reduce-over-jrubies! pool-context 4 #(format "InstanceID = %s" %))
  ;; and validate that we can read that value back from each instance
  (= #{0 1 2 3}
     (-> (jruby-testutils/reduce-over-jrubies! pool-context 4 (constantly "InstanceID"))
         set)))

(defn constant-defined?
  [jruby-instance]
  (let [sc (:scripting-container jruby-instance)]
    (.runScriptlet sc script-to-check-if-constant-is-defined)))

(defn verify-no-constants
  [pool-context]
  ;; verify that the constants are cleared out from the instances by looping
  ;; over them and expecting a 'NameError' when we reference the constant by name.
  (every? false?
          (jruby-testutils/reduce-over-jrubies!
            pool-context
            4
            (constantly script-to-check-if-constant-is-defined))))

(defn trigger-flush
  []
  (let [response (http-client/delete
                   "https://localhost:8140/puppet-admin-api/v1/jruby-pool"
                   ssl-request-options)]
    (= 204 (:status response))))

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
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)]
          (is (true? (trigger-flush)))
          @flush-complete)
        ;; now the pool is flushed, so the constants should be cleared
        (is (true? (verify-no-constants pool-context)))))))

(deftest ^:integration hold-instance-while-pool-flush-in-progress-test
  (testing "instance borrowed from old pool before pool flush begins and returned *after* new pool is available"
    (bootstrap/with-puppetserver-running
      app
      {:puppet-admin {:client-whitelist ["localhost"]}
       :jruby-puppet {:max-active-instances 4}}
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)
              ;; borrow an instance and hold the reference to it.
              instance (jruby-protocol/borrow-instance jruby-service)]
          ;; trigger a flush
          (is (true? (trigger-flush)))
          ;; borrow until we get an instance that doesn't have a constant,
          ;; so we'll know that the new pool is online
          (loop [instance (jruby-protocol/borrow-instance jruby-service)]
            (let [has-constant? (constant-defined? instance)]
              (jruby-protocol/return-instance jruby-service instance)
              (when has-constant?
                (recur (jruby-protocol/borrow-instance jruby-service)))))
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance)
          ;; wait until the flush is complete
          @flush-complete)
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context)))))))

