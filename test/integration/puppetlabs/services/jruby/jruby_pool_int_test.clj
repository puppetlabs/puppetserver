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
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :as authorization]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.request-handler.request-handler-service :as handler-service]
            [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
            [puppetlabs.services.config.puppet-server-config-service :as ps-config]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as handler-core]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [puppetlabs.puppetserver.testutils :as testutils :refer
             [ca-cert localhost-cert localhost-key ssl-request-options]]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/jruby_pool_int_test")

(use-fixtures :once
              schema-test/validate-schemas
              (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

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
  (loop [instance (jruby-protocol/borrow-instance jruby-service :wait-for-new-pool)]
    (let [has-constant? (constant-defined? instance)]
      (jruby-protocol/return-instance jruby-service instance :wait-for-new-pool)
      (when has-constant?
        (recur (jruby-protocol/borrow-instance jruby-service :wait-for-new-pool))))))

(defn borrow-until-desired-borrow-count
  [jruby-service desired-borrow-count]
  (loop [instance (jruby-protocol/borrow-instance jruby-service :borrow-until-desired-borrow-count)]
    (let [borrow-count (:borrow-count @(:state instance))]
      (jruby-protocol/return-instance jruby-service instance :borrow-until-desired-borrow-count)
      (if (< (inc borrow-count) desired-borrow-count)
        (recur (jruby-protocol/borrow-instance jruby-service :borrow-until-desired-borrow-count))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-flush-jruby-pool-test
  (testing "Flushing the pool results in all new JRuby instances"
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet {:max-active-instances 4}}
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
      {:jruby-puppet {:max-active-instances 4}}
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)
              ;; borrow an instance and hold the reference to it.
              instance (jruby-protocol/borrow-instance jruby-service
                         :hold-instance-while-pool-flush-in-progress-test)]
          ;; trigger a flush
          (is (true? (trigger-flush ssl-request-options)))
          ;; wait for the new pool to become available
          (wait-for-new-pool jruby-service)
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance :hold-instance-while-pool-flush-in-progress-test)
          ;; wait until the flush is complete
          @flush-complete)
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(deftest ^:integration hold-file-handle-on-instance-while-pool-flush-in-progress-test
  (testing "file handle opened from old pool instance is held open across pool flush"
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet {:max-active-instances 2}}
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 2)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)
              ;; borrow an instance and hold the reference to it.
              instance (jruby-protocol/borrow-instance jruby-service
                         :hold-instance-while-pool-flush-in-progress-test)
              sc (:scripting-container instance)]
          (.runScriptlet sc
                         (str "$unique_file = "
                              "Puppet::FileSystem::Uniquefile.new"
                              "('hold-instance-test-', './target')"))
          (try
            ;; trigger a flush
            (is (true? (trigger-flush ssl-request-options)))
            ;; wait for the new pool to become available
            (wait-for-new-pool jruby-service)

            (is (nil? (.runScriptlet sc "$unique_file.close"))
                "Unexpected response on attempt to close unique file")
            (finally
              (.runScriptlet sc "$unique_file.unlink")))
          ;; return the instance
          (jruby-protocol/return-instance jruby-service instance :hold-instance-while-pool-flush-in-progress-test)
          ;; wait until the flush is complete
          @flush-complete)
        ;; now the pool is flushed, and the constants should be cleared
        (is (true? (verify-no-constants pool-context 2)))))))

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
           puppet-admin/puppet-admin-service
           authorization/authorization-service]
          (merge (jruby-testutils/jruby-puppet-tk-config
                   (jruby-testutils/jruby-puppet-config {:max-active-instances      4
                                                         :max-requests-per-instance 10}))
                 {:webserver    (merge {:ssl-port 8140
                                        :ssl-host "localhost"}
                                       ssl-options)
                  :web-router-service
                                {:puppetlabs.services.ca.certificate-authority-service/certificate-authority-service ""
                                 :puppetlabs.services.master.master-service/master-service                           ""
                                 :puppetlabs.services.puppet-admin.puppet-admin-service/puppet-admin-service         "/puppet-admin-api"}})
          (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
                context (tk-services/service-context jruby-service)
                pool-context (:pool-context context)]
            ;; set a ruby constant in each instance so that we can recognize them.
            ;; this counts as one request for each instance.
            (is (true? (set-constants-and-verify pool-context 4)))
            (let [flush-complete (add-watch-for-flush-complete pool-context)
                  ;; borrow one instance and hold the reference to it, to prevent
                  ;; the flush operation from completing
                  instance1 (jruby-protocol/borrow-instance jruby-service
                              :max-requests-flush-while-pool-flush-in-progress-test)]
              ;; we are going to borrow and return a second instance until we get its
              ;; request count up to max-requests - 1, so that we can use it to test
              ;; flushing behavior the next time we return it.
              (borrow-until-desired-borrow-count jruby-service 9)
              ;; now we grab a reference to that instance and hold onto it for later.
              (let [instance2 (jruby-protocol/borrow-instance jruby-service
                                :max-requests-flush-while-pool-flush-in-progress-test)]
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
                (jruby-protocol/return-instance jruby-service instance2 :max-requests-flush-while-pool-flush-in-progress-test)
                (is (true? (check-jrubies-for-constant-counts pool-context 2 1))))

              ;; now we'll set the ruby constant on the 3 instances in the new pool
              (is (true? (set-constants-and-verify pool-context 3)))

              ;; and finally, we return the last instance from the old pool
              (jruby-protocol/return-instance jruby-service instance1 :max-requests-flush-while-pool-flush-in-progress-test)

              ;; wait until the flush is complete
              @flush-complete)

            ;; we should have three instances with the constant and one without.
            (is (true? (check-jrubies-for-constant-counts pool-context 3 1)))))))))

(defprotocol BonusService
  (bonus-service-fn [this]))

#_(deftest ^:integration test-restart-comes-back
  (testing "After a TK restart puppetserver can still handle requests"
    (let [call-seq (atom [])
          lc-fn (fn [context action] (swap! call-seq conj action) context)
          bonus-service (tk-services/service BonusService
                          [[:MasterService]]
                          (init [this context] (lc-fn context :init-bonus-service))
                          (start [this context] (lc-fn context :start-bonus-service))
                          (stop [this context] (lc-fn context :stop-bonus-service))
                          (bonus-service-fn [this] (lc-fn nil :bonus-service-fn)))]
      (bootstrap/with-puppetserver-running-with-services
       app
       (conj (tk-bootstrap/parse-bootstrap-config! bootstrap/dev-bootstrap-file) bonus-service)
       {:jruby-puppet {:max-active-instances 1}}
       (tk-internal/restart-tk-apps [app])
       (let [start (System/currentTimeMillis)]
         (while (and (not= (count @call-seq) 5)
                     (< (- (System/currentTimeMillis) start) 90000))
           (Thread/yield)))
       (is (= @call-seq [:init-bonus-service :start-bonus-service :stop-bonus-service :init-bonus-service :start-bonus-service]))
       (let [get-results (http-client/get "https://localhost:8140/puppet/v3/environments"
                                          bootstrap/catalog-request-options)]
         (is (= 200 (:status get-results))))))))

(deftest ^:integration test-503-when-app-shuts-down
  (testing "During a shutdown the agent requests result in a 503 response"
    (ks-testutils/with-no-jvm-shutdown-hooks
     (let [services [jruby/jruby-puppet-pooled-service profiler/puppet-profiler-service
                     handler-service/request-handler-service ps-config/puppet-server-config-service
                     jetty9/jetty9-service
                     vcs/versioned-code-service]
           config (-> (jruby-testutils/jruby-puppet-tk-config
                       (jruby-testutils/jruby-puppet-config {:max-active-instances 2}))
                      (assoc-in [:webserver :port] 8081))
           app (tk/boot-services-with-config services config)
           cert (ssl-utils/pem->cert
                 (str test-resources-dir "/localhost-cert.pem"))
           jruby-service (tk-app/get-service app :JRubyPuppetService)
           jruby-instance (jruby-protocol/borrow-instance jruby-service :i-want-this-instance)
           handler-service (tk-app/get-service app :RequestHandlerService)
           request {:uri "/puppet/v3/environments", :params {}, :headers {},
                    :request-method :GET, :body "", :ssl-client-cert cert, :content-type ""}
           ping-environment #(->> request (handler-core/wrap-params-for-jruby) (handler/handle-request handler-service))
           stop-complete? (future (tk-app/stop app))]
       (let [start (System/currentTimeMillis)]
         (logging/with-test-logging
          (while (and
                  (< (- (System/currentTimeMillis) start) 10000)
                  (not= 503 (:status (ping-environment))))
            (Thread/yield))
          (is (= 503 (:status (ping-environment)))))
         (jruby-protocol/return-instance jruby-service jruby-instance :i-want-this-instance)
         @stop-complete?
         (logging/with-test-logging
          (is (= 503 (:status (ping-environment))))))))))

(deftest ^:integration test-503-when-jruby-is-first-to-shutdown
  (testing "During a shutdown requests result in 503 http responses"
    (bootstrap/with-puppetserver-running
     app
     {:jruby-puppet {:max-active-instances 2}}
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           context (tk-services/service-context jruby-service)
           jruby-instance (jruby-protocol/borrow-instance jruby-service :i-want-this-instance)
           stop-complete? (future (tk-services/stop jruby-service context))
           ping-environment #(testutils/http-get "puppet/v3/environments")]
       (logging/with-test-logging
        (let [start (System/currentTimeMillis)]
          (while (and
                  (< (- (System/currentTimeMillis) start) 10000)
                  (not= 503 (:status (ping-environment))))
            (Thread/yield)))
        (is (= 503 (:status (ping-environment)))))
       (jruby-protocol/return-instance jruby-service jruby-instance :i-want-this-instance)
       @stop-complete?
       (let [app-context (tk-app/app-context app)]
         ;; We have to re-initialize the JRubyPuppetService here because
         ;; otherwise the tk-app/stop that is included in the
         ;; with-puppetserver-running macro will fail, as the
         ;; JRubyPuppetService is already stopped.
         (swap! app-context assoc-in [:service-contexts :JRubyPuppetService] {})
         (tk-internal/run-lifecycle-fn! app-context tk-services/init "init" :JRubyPuppetService jruby-service)
         (tk-internal/run-lifecycle-fn! app-context tk-services/start "start" :JRubyPuppetService jruby-service))))))