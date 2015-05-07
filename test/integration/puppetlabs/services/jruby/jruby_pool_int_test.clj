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
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting]
            [puppetlabs.services.puppet-admin.puppet-admin-service :as puppet-admin]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]
            [clojure.tools.logging :as log]))

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
  "! $instance_id.nil?")

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
  [pool-context num-instances]
  ;; here we set a variable called 'instance_id' in each instance
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    #(format "$instance_id = %s" %))
  ;; and validate that we can read that value back from each instance
  (= (set (range num-instances))
     (-> (jruby-testutils/reduce-over-jrubies!
           pool-context
           num-instances
           (constantly "$instance_id"))
         set)))

(defn constant-defined?
  [jruby-instance]
  (let [sc (:scripting-container jruby-instance)]
    (.runScriptlet sc script-to-check-if-constant-is-defined)))

(defn check-all-jrubies-for-constants
  [pool-context num-instances]
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    (constantly script-to-check-if-constant-is-defined)))

(defn check-jrubies-for-constant-counts
  [pool-context expected-num-true expected-num-false]
  (let [constants (check-all-jrubies-for-constants
                    pool-context
                    (+ expected-num-false expected-num-true))]
    (and (= (+ expected-num-false expected-num-true) (count constants))
         (= expected-num-true (count (filter true? constants)))
         (= expected-num-false (count (filter false? constants))))))

(defn verify-no-constants
  [pool-context num-instances]
  ;; verify that the constants are cleared out from the instances by looping
  ;; over them and expecting a 'NameError' when we reference the constant by name.
  (every? false? (check-all-jrubies-for-constants pool-context num-instances)))

(defn trigger-flush
  [ssl-options]
  (let [response (http-client/delete
                   "https://localhost:8140/puppet-admin-api/v1/jruby-pool"
                   ssl-options)]
    (= 204 (:status response))))

(defn wait-for-new-pool
  [jruby-service]
  ;; borrow until we get an instance that doesn't have a constant,
  ;; so we'll know that the new pool is online
  (loop [instance (jruby-protocol/borrow-instance jruby-service)]
    (let [has-constant? (constant-defined? instance)]
      (jruby-protocol/return-instance jruby-service instance)
      (when has-constant?
        (recur (jruby-protocol/borrow-instance jruby-service))))))

(defn borrow-until-desired-borrow-count
  [jruby-service desired-borrow-count]
  (loop [instance (jruby-protocol/borrow-instance jruby-service)]
    (let [borrow-count (:borrow-count @(:state instance))]
      (jruby-protocol/return-instance jruby-service instance)
      (if (< (inc borrow-count) desired-borrow-count)
        (recur (jruby-protocol/borrow-instance jruby-service))))))

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
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)]
          (is (true? (trigger-flush ssl-request-options)))
          @flush-complete)
        ;; now the pool is flushed, so the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

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
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)
              ;; borrow an instance and hold the reference to it.
              instance (jruby-protocol/borrow-instance jruby-service)]
          ;; trigger a flush
          (is (true? (trigger-flush ssl-request-options)))
          ;; wait for the new pool to become available
          (wait-for-new-pool jruby-service)
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance)
          ;; wait until the flush is complete
          @flush-complete)
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration max-requests-flush-while-pool-flush-in-progress-test
  (testing "instance from new pool hits max-requests while flush in progress"
    (let [test-pem #(str "./dev-resources/puppetlabs/services/jruby/jruby_pool_int_test/" %)
          ssl-options {:ssl-ca-cert (test-pem "ca-cert.pem")
                       :ssl-cert (test-pem "localhost-cert.pem")
                       :ssl-key (test-pem "localhost-privkey.pem")}]
      (jruby-testutils/with-mock-pool-instance-fixture
        (tk-testutils/with-app-with-config
          app
          [profiler/puppet-profiler-service
           jruby/jruby-puppet-pooled-service
           jetty9/jetty9-service
           webrouting/webrouting-service
           puppet-admin/puppet-admin-service]
          (merge (jruby-testutils/jruby-puppet-tk-config
                   (jruby-testutils/jruby-puppet-config {:max-active-instances      4
                                                         :max-requests-per-instance 10}))
                 {:webserver    (merge {:ssl-port 8140
                                        :ssl-host "localhost"}
                                       ssl-options)
                  :web-router-service
                                {:puppetlabs.services.ca.certificate-authority-service/certificate-authority-service ""
                                 :puppetlabs.services.master.master-service/master-service                           ""
                                 :puppetlabs.services.puppet-admin.puppet-admin-service/puppet-admin-service         "/puppet-admin-api"}
                  :puppet-admin {:client-whitelist ["localhost"]}})
          (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
                context (tk-services/service-context jruby-service)
                pool-context (:pool-context context)]
            ;; set a ruby constant in each instance so that we can recognize them.
            ;; this counts as one request for each instance.
            (is (true? (set-constants-and-verify pool-context 4)))
            (let [flush-complete (add-watch-for-flush-complete pool-context)
                  ;; borrow one instance and hold the reference to it, to prevent
                  ;; the flush operation from completing
                  instance1 (jruby-protocol/borrow-instance jruby-service)]
              ;; we are going to borrow and return a second instance until we get its
              ;; request count up to max-requests - 1, so that we can use it to test
              ;; flushing behavior the next time we return it.
              (borrow-until-desired-borrow-count jruby-service 9)
              ;; now we grab a reference to that instance and hold onto it for later.
              (let [instance2 (jruby-protocol/borrow-instance jruby-service)]
                (is (= 9 (:borrow-count @(:state instance2))))

                ;; trigger a flush
                (is (true? (trigger-flush ssl-options)))
                ;; wait for the new pool to become available
                (wait-for-new-pool jruby-service)
                ;; there will only be two instances in the new pool, because we are holding
                ;; references to two from the old pool.
                (is (true? (set-constants-and-verify pool-context 2)))
                ;; borrow and return instance from the new pool until an instance flush is triggered
                (borrow-until-desired-borrow-count jruby-service 10)

                ;; at this point, we still have the main flush in progress, waiting for us
                ;; to release the two instances from the old pool.  we should also have
                ;; caused a flush of one of the two instances in the new pool, meaning that
                ;; exactly one of the two in the new pool should have the ruby constant defined.
                (is (true? (check-jrubies-for-constant-counts pool-context 1 1)))

                ;; now we'll set the ruby constants on both instances in the new pool
                (is (true? (set-constants-and-verify pool-context 2)))

                ;; now we're going to return instance2 to the pool.  This should cause it
                ;; to get flushed, but after that, the main pool flush operation should
                ;; pull it out of the old pool and create a new instance in the new pool
                ;; to replace it.  So we should end up with 3 instances in the new pool,
                ;; two of which should have the ruby constants and one of which should not.
                (jruby-protocol/return-instance jruby-service instance2)
                (is (true? (check-jrubies-for-constant-counts pool-context 2 1))))

              ;; now we'll set the ruby constant on the 3 instances in the new pool
              (is (true? (set-constants-and-verify pool-context 3)))

              ;; and finally, we return the last instance from the old pool
              (jruby-protocol/return-instance jruby-service instance1)

              ;; wait until the flush is complete
              @flush-complete)

            ;; we should have three instances with the constant and one without.
            (is (true? (check-jrubies-for-constant-counts pool-context 3 1)))))))))