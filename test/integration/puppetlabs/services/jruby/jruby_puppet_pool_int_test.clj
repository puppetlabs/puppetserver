(ns puppetlabs.services.jruby.jruby-puppet-pool-int-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.jruby.jruby-metrics-service :as jruby-metrics-service]
            [puppetlabs.services.request-handler.request-handler-service :as handler-service]
            [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
            [puppetlabs.services.config.puppet-server-config-service :as ps-config]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as handler-core]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [puppetlabs.puppetserver.testutils :as testutils :refer
             [ca-cert localhost-cert localhost-key ssl-request-options]]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.trapperkeeper.services.protocols.metrics :as metrics-protocol]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.http.client.metrics :as metrics]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils])
  (:import (org.jruby RubyInstanceConfig$CompileMode RubyInstanceConfig$ProfilingMode)
           (com.codahale.metrics MetricRegistry)
           (org.jruby.runtime Constants)))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/jruby_pool_int_test")

(use-fixtures :once
              schema-test/validate-schemas
              (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def default-borrow-timeout 300000)

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(defn timed-deref
  [ref]
  (deref ref 240000 :timed-out))

(defn get-stack-trace-for-thread-as-str
  [stack-trace-elements]
  (reduce
   (fn [acc stack-trace-element]
     (str acc
          "  "
          (.getClassName stack-trace-element)
          "."
          (.getMethodName stack-trace-element)
          "("
          (.getFileName stack-trace-element)
          ":"
          (.getLineNumber stack-trace-element)
          ")"
          "\n"))
   ""
   stack-trace-elements))

(defn get-all-stack-traces-as-str
  []
  (reduce
   (fn [acc thread-stack-element]
     (let [thread (key thread-stack-element)]
       (str acc
            "\""
            (.getName thread)
            "\" id="
            (.getId thread)
            " state="
            (.getState thread)
            "\n"
            (get-stack-trace-for-thread-as-str
             (val thread-stack-element)))))
   ""
   (Thread/getAllStackTraces)))

(def script-to-check-if-constant-is-defined
  "! $instance_id.nil?")

(defn add-watch-for-flush-complete
  [pool-context]
  (let [flush-complete (promise)]
    (add-watch (get-in pool-context [:internal :modify-instance-agent]) :flush-callback
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-flush-jruby-pool-test
  (testing "Flushing the pool results in all new JRuby instances"
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet {:gem-path gem-path
                      :max-active-instances 4
                      :borrow-timeout default-borrow-timeout}}
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        ;; set a ruby constant in each instance so that we can recognize them
        (is (true? (set-constants-and-verify pool-context 4)))
        (let [flush-complete (add-watch-for-flush-complete pool-context)]
          (is (true? (trigger-flush ssl-request-options)))
          (is (true? (timed-deref flush-complete))
              (str "timed out waiting for the flush to complete, stack:\n"
                   (get-all-stack-traces-as-str))))
        ;; now the pool is flushed, so the constants should be cleared
        (is (true? (verify-no-constants pool-context 4)))))))

(defprotocol BonusService
  (bonus-service-fn [this]))

(deftest ^:integration test-restart-comes-back
  (testing "After a TK restart puppetserver can still handle requests"
    (let [call-seq (atom [])
          debug-log "./target/test-restart-comes-back.log"
          lc-fn (fn [context action] (swap! call-seq conj action) context)
          bonus-service (tk-services/service BonusService
                          [[:MasterService]]
                          (init [this context] (lc-fn context :init-bonus-service))
                          (start [this context] (lc-fn context :start-bonus-service))
                          (stop [this context] (lc-fn context :stop-bonus-service))
                          (bonus-service-fn [this] (lc-fn nil :bonus-service-fn)))
          config-overrides {:global {:logging-config
                                     (str "./dev-resources/puppetlabs/services/"
                                          "jruby/jruby_pool_int_test/"
                                          "logback-test-restart-comes-back.xml")}
                            :jruby-puppet {:max-active-instances 1
                                           :borrow-timeout default-borrow-timeout}}
          config (bootstrap/load-dev-config-with-overrides config-overrides)
          mock-jruby-puppet-fn (jruby-testutils/create-mock-jruby-puppet-fn-with-handle-response-params
                                200
                                "success response from restart test")]
      (fs/delete debug-log)
      (tk-testutils/with-app-with-config
       app
       (conj (bootstrap/services-from-dev-bootstrap-plus-mock-jruby-pool-manager-service
              config
              mock-jruby-puppet-fn)
             bonus-service)
       config
       (tk-internal/restart-tk-apps [app])
       (let [start (System/currentTimeMillis)]
         (while (and (not= (count @call-seq) 5)
                     (< (- (System/currentTimeMillis) start) 300000))
           (Thread/yield)))
       (let [shutdown-service (tk-app/get-service app :ShutdownService)]
         (is (nil? (tk-internal/get-shutdown-reason shutdown-service))
             "shutdown reason was unexpectedly set after restart"))
       (is (= @call-seq
              [:init-bonus-service :start-bonus-service :stop-bonus-service :init-bonus-service :start-bonus-service])
           (str "dumping puppetserver.log\n" (slurp debug-log)))
       (let [get-results (http-client/get "https://localhost:8140/puppet/v3/environments"
                                          testutils/catalog-request-options)]
         (is (= 200 (:status get-results)))
         (is (= "success response from restart test" (:body get-results))))))))

(deftest ^:integration test-503-when-app-shuts-down
  (testing "After the app is shutdown the agent requests result in a 503 response"
    (ks-testutils/with-no-jvm-shutdown-hooks
     (let [config (-> (jruby-testutils/jruby-puppet-tk-config
                       (jruby-testutils/jruby-puppet-config {:max-active-instances 2
                                                             :borrow-timeout
                                                             default-borrow-timeout}))
                      (assoc :webserver {:port 8081
                                         :shutdown-timeout-seconds 1}))
           mock-jruby-puppet-fn (jruby-testutils/create-mock-jruby-puppet-fn-with-handle-response-params
                                 200
                                 (str "response body doesn't matter, just "
                                      "provide a successful response so the "
                                      "test knows the webserver is still running"))
           services (jruby-testutils/add-mock-jruby-pool-manager-service
                     (conj jruby-testutils/jruby-service-and-dependencies
                           jruby-metrics-service/jruby-metrics-service
                           handler-service/request-handler-service
                           ps-config/puppet-server-config-service
                           vcs/versioned-code-service)
                     config
                     mock-jruby-puppet-fn)
           app (tk/boot-services-with-config services config)]
       (try
         (let [cert (ssl-utils/pem->cert
                     (str test-resources-dir "/localhost-cert.pem"))
               handler-service (tk-app/get-service app :RequestHandlerService)
               request {:uri "/puppet/v3/environments", :params {}, :headers {},
                        :request-method :get, :body "", :ssl-client-cert cert, :content-type ""}
               ping-environment #(->> request (handler-core/wrap-params-for-jruby) (handler/handle-request handler-service))
               ping-before-stop (ping-environment)
               stop-complete? (future (tk-app/stop app))]
           (is (= 200 (:status ping-before-stop))
               "environment request before stop failed")
           (let [start (System/currentTimeMillis)]
             (logging/with-test-logging
              (while (and
                      (< (- (System/currentTimeMillis) start) 10000)
                      (not= 503 (:status (ping-environment))))
                (Thread/yield))
              (is (= 503 (:status (ping-environment)))))
             (is (not= :timed-out (timed-deref stop-complete?))
                 (str "timed out waiting for the stop to complete, stack:\n"
                      (get-all-stack-traces-as-str)))
             (logging/with-test-logging
              (is (= 503 (:status (ping-environment)))))))
         (finally
           ;; Stop the tk app.  In success cases, this will end up being done
           ;; twice - which is benign.  This is done in the finally block so
           ;; that it is done even if the test errors out prematurely.  Not
           ;; doing so could leave a Jetty webserver running indefinitely -
           ;; causing many tests downstream from this one that try to create
           ;; new Jetty servers to fail.
           (tk-app/stop app)))))))

(deftest ^:integration test-503-when-jruby-is-first-to-shutdown
  (testing "After the jruby service is shutdown, requests result in 503 http responses"
    ;; The difference between this test and the test-503-when-app-shuts-down test is that
    ;; This test only shuts down the jruby service as opposed to the entire app. This way
    ;; we can test that the behavior is the same even if the app is in a partial shutdown
    ;; state when a request comes in
    (bootstrap/with-puppetserver-running-with-mock-jruby-puppet-fn
     "JRuby mocking is safe here, because we're just looking to see that the
      HTTP response for an environment request transitions from success to
      a 503 failure after the Jetty webserver has been shut down. Having a
      'real' response body to the successful environment request isn't
      essential to observing the transition from success to failure."
     app
     {:jruby-puppet {:gem-path gem-path
                     :max-active-instances 2
                     :borrow-timeout default-borrow-timeout}}
     (jruby-testutils/create-mock-jruby-puppet-fn-with-handle-response-params
      200
      "our env request has been mocked!")
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           context (tk-services/service-context jruby-service)
           ping-environment #(testutils/http-get "puppet/v3/environments")
           ping-before-stop (ping-environment)
           stop-complete? (future (tk-services/stop jruby-service context))]
       (is (= 200 (:status ping-before-stop))
           "environment request before stop failed")
       (is (= "our env request has been mocked!" (:body ping-before-stop))
           "environment response did not have our mock response body")
       (logging/with-test-logging
        (let [start (System/currentTimeMillis)]
          (while (and
                  (< (- (System/currentTimeMillis) start) 10000)
                  (not= 503 (:status (ping-environment))))
            (Thread/yield)))
        (is (= 503 (:status (ping-environment)))))

       (is (not= :timed-out (timed-deref stop-complete?))
           (str "timed out waiting for the stop to complete, stack:\n"
                (get-all-stack-traces-as-str)))
       (let [app-context (tk-app/app-context app)]
         ;; We have to re-initialize the JRubyPuppetService here because
         ;; otherwise the tk-app/stop that is included in the
         ;; with-puppetserver-running macro will fail, as the
         ;; JRubyPuppetService is already stopped.
         (swap! app-context assoc-in [:service-contexts :JRubyPuppetService] {})
         (tk-internal/run-lifecycle-fn! app-context tk-services/init "init" :JRubyPuppetService jruby-service)
         (tk-internal/run-lifecycle-fn! app-context tk-services/start "start" :JRubyPuppetService jruby-service))))))

(deftest ^:integration settings-plumbed-into-jruby-container
  (testing "setting plumbed into jruby container for"
    (let [profiler-file (str (ks/temp-file-name "foo"))
          jruby-puppet-config (jruby-testutils/jruby-puppet-config {:max-active-instances 1
                                                                    :compile-mode :jit
                                                                    :profiling-mode :flat
                                                                    :profiler-output-file profiler-file})
          config (assoc
                  (jruby-testutils/jruby-puppet-tk-config jruby-puppet-config)
                   :http-client {:connect-timeout-milliseconds 2
                                 :idle-timeout-milliseconds 5
                                 :cipher-suites ["TLS_RSA_WITH_AES_256_CBC_SHA256"
                                                 "TLS_RSA_WITH_AES_256_CBC_SHA"]
                                 :ssl-protocols ["TLSv1" "TLSv1.2"]})]
      (logutils/with-test-logging
       (tk-testutils/with-app-with-config
        app
        jruby-testutils/jruby-service-and-dependencies
        config
        (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
              jruby-instance (jruby-testutils/borrow-instance jruby-service :test)
              container (:scripting-container jruby-instance)]
          (try
            (testing "compile mode"
              (is (= RubyInstanceConfig$CompileMode/JIT
                     (.getCompileMode container))))
            (testing "profiling mode"
              (is (= RubyInstanceConfig$ProfilingMode/FLAT
                     (.getProfilingMode container))))
            (let [settings (into {} (.runScriptlet container
                                                   "java.util.HashMap.new
                                                      (Puppet::Server::HttpClient.settings)"))]
              (testing "http_connect_timeout_milliseconds"
                (is (= 2 (settings "http_connect_timeout_milliseconds"))))
              (testing "http_idle_timeout_milliseconds"
                (is (= 5 (settings "http_idle_timeout_milliseconds"))))
              (testing "cipher_suites"
                (is (= ["TLS_RSA_WITH_AES_256_CBC_SHA256"
                        "TLS_RSA_WITH_AES_256_CBC_SHA"]
                       (into [] (settings "cipher_suites")))))
              (testing "ssl_protocols"
                (is (= ["TLSv1" "TLSv1.2"]
                       (into [] (settings "ssl_protocols"))))))
            (finally
              (jruby-testutils/return-instance jruby-service jruby-instance :settings-plumbed-test)

              ;; Because we add the current datetime and scripting container
              ;; hashcode to the profiler filename we need to glob for it here.
              (let [profiler-files (fs/glob (fs/parent profiler-file)
                                            (str (fs/base-name profiler-file) "*"))]
                (is (= 1 (count profiler-files))))))))))))

(deftest ^:integration http-client-metrics-enabled-test
  (let [config (jruby-testutils/jruby-puppet-tk-config (jruby-testutils/jruby-puppet-config))]
    (testing "when http client metrics are disabled, http client does not use a metric registry"
      (tk-testutils/with-app-with-config
       app
       jruby-testutils/jruby-service-and-dependencies
       (assoc-in config [:http-client :metrics-enabled] false)
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             jruby-instance (jruby-testutils/borrow-instance jruby-service :no-metrics-test)
             container (:scripting-container jruby-instance)]
         (try
           (let [client (.runScriptlet container "Puppet::Server::HttpClient.client")]
             (testing "client does not have a registry"
               (is (= nil (.getMetricRegistry client)))))
           (finally
             (jruby-testutils/return-instance jruby-service jruby-instance :no-metrics-test))))))
    (testing "when http client metrics are enabled, http client uses a metric registry"
      (tk-testutils/with-app-with-config
       app
       jruby-testutils/jruby-service-and-dependencies
       config
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             jruby-instance (jruby-testutils/borrow-instance jruby-service :metrics-test)
             container (:scripting-container jruby-instance)]
         (try
           (let [client (.runScriptlet container "Puppet::Server::HttpClient.client")]
             (testing "client has a registry"
               (is (instance? MetricRegistry (.getMetricRegistry client))))
             (testing "server id is passed through to client"
               (is (= "puppetlabs.localhost.http-client.experimental"
                      (.getMetricNamespace client)))))
           (finally
             (jruby-testutils/return-instance jruby-service jruby-instance :metrics-test))))))))

(tk/defservice test-metric-web-service
               [[:WebserverService add-ring-handler]]
  (init [this context]
        (add-ring-handler
         (fn [_] {:status 200 :body "Hello, World!"}) "/hello")
        context))

(deftest ^:integration http-client-metrics-test
  (let [config (jruby-testutils/jruby-puppet-tk-config (jruby-testutils/jruby-puppet-config))
        add-metric-ns #(format "puppetlabs.localhost.http-client.experimental.%s.full-response" %)]
    (testing "metrics get registered with metric registry when http client makes requests"
      (tk-testutils/with-app-with-config
       app
       (conj jruby-testutils/jruby-service-and-dependencies
             test-metric-web-service)
       (assoc-in config [:webserver :host] "localhost")
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             jruby-instance (jruby-testutils/borrow-instance jruby-service :http-client-metrics-test)
             container (:scripting-container jruby-instance)]
         (try
           (.runScriptlet container
                          (format "$c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                                  8080 false))
           (let [metrics-svc (tk-app/get-service app :MetricsService)
                 metric-registry (metrics-protocol/get-metrics-registry metrics-svc :puppetserver)]
             (testing "metric registry has no metrics when no http requests have been made"
               (is (= {:url [] :url-and-method [] :metric-id []}
                      (metrics/get-client-metrics-data metric-registry))))
             (testing "metric registry does not have any metrics by default"
               (testing "GET request"
                 (.runScriptlet container "$c.get('/hello', {})")
                 (let [metrics-data (metrics/get-client-metrics-data metric-registry)]
                   (is (= [] (:url metrics-data)))
                   (is (= [] (:url-and-method metrics-data)))))
               (testing "POST request"
                 (.runScriptlet container "$c.post('/hello', 'body', {})")
                 (let [metrics-data (metrics/get-client-metrics-data metric-registry)
                       url-metrics-data (:url metrics-data)]
                   (is (= [] (:url metrics-data)))
                   (is (= [] (:url-and-method metrics-data)))))
               (testing "no metric-id metrics are registered"
                 (is (= [] (:metric-id (metrics/get-client-metrics-data metric-registry))))))
             (testing (str "metric registry has metric-id metrics after http requests have been made"
                           " with metric-id specified")
               (testing "GET request"
                 (.runScriptlet container
                                "$c.get('/hello', {}, {:metric_id => ['foo', 'get']})")
                 (let [metric-id-data (:metric-id (metrics/get-client-metrics-data metric-registry))]
                   (is (= 2 (count metric-id-data)))
                   (is (= #{(add-metric-ns "with-metric-id.foo")
                            (add-metric-ns "with-metric-id.foo.get")}
                          (set (map :metric-name metric-id-data)))))
                 (is (= 1 (:count (first (metrics/get-client-metrics-data-by-metric-id
                                          metric-registry ["foo"])))))
                 (is (= 1 (:count (first (metrics/get-client-metrics-data-by-metric-id
                                          metric-registry ["foo" "get"]))))))
               (testing "POST request"
                 (.runScriptlet container
                                "$c.post('/hello', 'body', {}, {:metric_id => ['foo', 'post']})")
                 (let [metric-id-data (:metric-id (metrics/get-client-metrics-data metric-registry))]
                   ;; there should now be two requests with the 'foo' metric id, one with 'foo.get',
                   ;; and one with 'foo.post'
                   (is (= 3 (count metric-id-data)))
                   (is (= #{(add-metric-ns "with-metric-id.foo")
                            (add-metric-ns "with-metric-id.foo.get")
                            (add-metric-ns "with-metric-id.foo.post")}
                          (set (map :metric-name metric-id-data)))))
                 (is (= 2 (:count (first (metrics/get-client-metrics-data-by-metric-id
                                          metric-registry ["foo"])))))
                 (is (= 1 (:count (first (metrics/get-client-metrics-data-by-metric-id
                                          metric-registry ["foo" "get"])))))
                 (is (= 1 (:count (first (metrics/get-client-metrics-data-by-metric-id
                                          metric-registry ["foo" "post"]))))))))
           (finally
             (jruby-testutils/return-instance jruby-service jruby-instance :http-client-metrics-test))))))))

(deftest create-jruby-instance-test
  (testing "Directories can be configured programatically
            (and take precedence over puppet.conf)"
    (tk-testutils/with-app-with-config
     app
     jruby-testutils/jruby-service-and-dependencies
     (jruby-testutils/jruby-puppet-tk-config
      (jruby-testutils/jruby-puppet-config
       {:ruby-load-path  jruby-testutils/ruby-load-path
        :gem-home        jruby-testutils/gem-home
        :gem-path        jruby-testutils/gem-path
        :master-conf-dir jruby-testutils/conf-dir
        :master-code-dir jruby-testutils/code-dir
        :master-var-dir  jruby-testutils/var-dir
        :master-run-dir  jruby-testutils/run-dir
        :master-log-dir  jruby-testutils/log-dir}))
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           jruby-instance (jruby-testutils/borrow-instance jruby-service :test)
           jruby-puppet (:jruby-puppet jruby-instance)]
       (try
         (are [setting expected]
           (= (-> expected
                  (ks/normalized-path)
                  (ks/absolute-path))
              (.getSetting jruby-puppet setting))
           "confdir" jruby-testutils/conf-dir
           "codedir" jruby-testutils/code-dir
           "vardir" jruby-testutils/var-dir
           "rundir" jruby-testutils/run-dir
           "logdir" jruby-testutils/log-dir)
         (finally
           (jruby-testutils/return-instance jruby-service jruby-instance :settings-plumbed-test))))))

  (testing "Settings from Ruby Puppet are available"
    (tk-testutils/with-app-with-config
     app
     jruby-testutils/jruby-service-and-dependencies
     (jruby-testutils/jruby-puppet-tk-config
      (jruby-testutils/jruby-puppet-config))
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           jruby-instance (jruby-testutils/borrow-instance jruby-service :test)
           jruby-puppet (:jruby-puppet jruby-instance)]
       (try
         (testing "Various data types"
           (is (= "ldap" (.getSetting jruby-puppet "ldapserver")))
           (is (= 8140 (.getSetting jruby-puppet "masterport")))
           (is (= false (.getSetting jruby-puppet "onetime"))))
         (finally
           (jruby-testutils/return-instance jruby-service jruby-instance :settings-plumbed-test)))))))

(deftest jruby-environment-vars-test
  (testing "Make sure that the environment variables whitelisted in puppetserver.conf are being set"
    (tk-testutils/with-app-with-config
      app
      jruby-testutils/jruby-service-and-dependencies
      (let [tmp-conf (ks/temp-file "puppetserver" ".conf")]
        (spit tmp-conf
              "environment-vars: { \"FOO\": ${HOME} }")
        (jruby-testutils/jruby-puppet-tk-config
          (jruby-testutils/jruby-puppet-config
            (merge
              {:ruby-load-path   jruby-testutils/ruby-load-path
               :gem-home         jruby-testutils/gem-home
               :gem-path         jruby-testutils/gem-path
               :master-conf-dir  jruby-testutils/conf-dir
               :master-code-dir  jruby-testutils/code-dir
               :master-var-dir   jruby-testutils/var-dir
               :master-run-dir   jruby-testutils/run-dir
               :master-log-dir   jruby-testutils/log-dir}
              (tk-config/load-config (.getPath tmp-conf))))))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            jruby-instance (jruby-testutils/borrow-instance jruby-service :test)
            jruby-scripting-container (:scripting-container jruby-instance)
            jruby-env (.runScriptlet jruby-scripting-container "ENV")]
        (try
         ;; Note that other environment variables are allowed through
         ;; (HTTP_PROXY, HTTPS_PROXY, NO_PROXY, or their lowercase
         ;; variants) but are not expected to be set in most environments.
         ;; However, in order to make this test more robust, these variables
         ;; are always filtered out.
          (is (= #{"HOME" "PATH" "GEM_HOME" "GEM_PATH"
                   "JARS_NO_REQUIRE" "JARS_REQUIRE" "RUBY" "FOO"}
                 (set (remove (set jruby-core/proxy-vars-allowed-list) (keys jruby-env)))))
          (is (= (.get jruby-env "FOO") (System/getenv "HOME")))
          (finally
            (jruby-testutils/return-instance jruby-service jruby-instance :test)))))))

(deftest master-termination-test
  (testing "Flushing the pool causes masters to be terminated"
    (with-redefs [jruby-puppet-core/cleanup-fn
                  (fn [instance]
                    (log/info "In cleanup fn")
                    (.terminate (:jruby-puppet instance)))]
      (logging/with-test-logging
        (tk-testutils/with-app-with-config
         app
         jruby-testutils/jruby-service-and-dependencies
         (jruby-testutils/jruby-puppet-tk-config
          (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
         (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
               pool-context (jruby-protocol/get-pool-context jruby-service)
               pool-agent (jruby-agents/get-modify-instance-agent pool-context)]
           (jruby-protocol/flush-jruby-pool! jruby-service)
           ; wait until the flush is complete
           (await pool-agent)
           (is (logged? #"In cleanup fn"))))))))

(deftest compat-version-in-config-throws-exception-test
  (testing "compat-version setting in configuration throws exception"
    (logging/with-test-logging
     (is (thrown-with-msg?
          IllegalArgumentException
          #"jruby-puppet.compat-version setting no longer supported"
          (tk-testutils/with-app-with-config
           app
           jruby-testutils/jruby-service-and-dependencies
           (assoc-in
            (jruby-testutils/jruby-puppet-tk-config
             (jruby-testutils/jruby-puppet-config))
            [:jruby-puppet :compat-version]
            "2.0")))))))
