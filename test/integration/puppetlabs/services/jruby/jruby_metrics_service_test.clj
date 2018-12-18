(ns puppetlabs.services.jruby.jruby-metrics-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-service]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.services.jruby.jruby-metrics-service :as jruby-metrics-service]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as scheduler-service]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :as metrics-service]
            [puppetlabs.services.request-handler.request-handler-service :as request-handler-service]
            [puppetlabs.services.versioned-code-service.versioned-code-service :as versioned-code-service]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as jruby-pool-manager-service]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.protocols.jruby-metrics :as jruby-metrics-protocol]
            [puppetlabs.services.protocols.puppet-server-config :as ps-config-protocol]
            [puppetlabs.trapperkeeper.services.status.status-service :as status-service]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap-testutils]
            [schema.test :as schema-test]
            [puppetlabs.metrics :as metrics]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.comidi :as comidi]
            [cemerick.url :as url]
            [puppetlabs.testutils.task-coordinator :as coordinator]
            [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]
            [clojure.tools.logging :as log]
            [schema.core :as schema]
            [cheshire.core :as json])
  (:import (com.puppetlabs.puppetserver JRubyPuppetResponse JRubyPuppet)
           (clojure.lang IFn Atom)
           (java.util.concurrent TimeUnit)))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Config / constants

(def default-test-config
  (-> {:jruby-puppet {:max-active-instances 2}
       :metrics {:server-id "localhost"}}
      bootstrap-testutils/load-dev-config-with-overrides
      (assoc :webserver {:port 8140 :host "localhost"})))

(def request-phases [:http-handler-invoked :borrowed-jruby :returning-jruby :request-complete])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Basic utility fns

(defn http-get
  [uri]
  (http-client/get uri {:as :text}))

(defn timestamp-after?
  [start-time event-time]
  (let [now (System/currentTimeMillis)]
    (if (and (<= start-time event-time)
             (>= now event-time))
      true
      (throw (IllegalStateException.
              (format
               "Timestamp seems wrong: '%s'; expected it to be between '%s' and '%s'"
               event-time
               start-time
               now))))))

(defn async-request
  ([coordinator request-id uri]
   (async-request coordinator request-id uri nil))
  ([coordinator request-id uri phase]
    ;; add the request id into the url as a query param
    ;; for use with the coordinator
   (let [orig-url (url/url (str "http://localhost:8140" uri))
         query (assoc (:query orig-url) "request-id" request-id)
         url (assoc orig-url :query query)
         ;; our request function to pass to the coordinator
         ;; is just a simple HTTP GET.
         req-fn (fn [] (http-get (str url)))]
     (coordinator/initialize-task coordinator request-id req-fn phase))))

(defn sync-request
  [coordinator request-id uri]
  (async-request coordinator request-id uri :request-complete)
  (coordinator/final-result coordinator request-id))

(defn current-jruby-status-metrics
  []
  (-> (str "http://localhost:8140/status/v1/"
           "services/jruby-metrics?level=debug")
      http-get
      :body
      (json/parse-string true)
      (get-in [:status :experimental :metrics])))

(defn jruby-status-metric-counters
  [jruby-status-metrics]
  (-> jruby-status-metrics
      (select-keys
       [:borrow-count
        :borrow-retry-count
        :borrow-timeout-count
        :num-free-jrubies
        :num-jrubies
        :requested-count
        :return-count])
      (assoc :current-borrowed-instances (count (:borrowed-instances
                                                 jruby-status-metrics))
             :current-requested-instances (count (:requested-instances
                                                  jruby-status-metrics)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test services/mocks

;; The request handler service depends on the PuppetServerConfigService,
;; but we don't actually need any of the puppet config values, so here
;; we just create a mock services that passes through to the normal
;; TK config service to make the tests run faster.
(tk/defservice mock-puppetserver-config-service
  ps-config-protocol/PuppetServerConfigService
  [[:ConfigService get-config get-in-config]]
  (get-config [this] (get-config))
  (get-in-config [this ks] (get-in-config ks)))

(schema/defn ^:always-validate comidi-handler-service :- (schema/protocol tk-services/ServiceDefinition)
  [coordinator :- (schema/protocol coordinator/TaskCoordinator)]
  (tk/service
    [[:WebserverService add-ring-handler]
     [:RequestHandlerService handle-request]]
    (start [this context]
      (let [app-request-handler (fn [request]
                                  ;; we pass the request-id as a query-param, so that
                                  ;; the comidi handler can interact with the
                                  ;; test request coordinator
                                  (let [request-id (-> (:query-string request)
                                                     url/query->map
                                                     (get "request-id"))]
                                    ;; notify the coordinator that we've begun to handle the request
                                    (coordinator/notify-task-progress coordinator request-id :http-handler-invoked)
                                    ;; delegate to the jruby request handler
                                    (let [resp (handle-request request)]
                                      ;; notify the coordinator that the request is complete
                                      (coordinator/notify-task-progress coordinator request-id :request-complete)
                                      resp)))
            routes (comidi/context "/foo"
                     (comidi/routes
                       ;; Route handler that isn't wrapped with the request
                       ;; coordinator.
                       (comidi/GET ["/uncoord/" :uncoord] request
                         (handle-request request))
                       (comidi/GET ["/bar/" :bar] request
                         (app-request-handler request))
                       (comidi/GET ["/baz/" :baz] request
                         (app-request-handler request))))
            handler (-> routes
                      comidi/routes->handler
                      (comidi/wrap-with-route-metadata routes))]
        (add-ring-handler handler "/foo"))
      context)))

;; here we're creating a custom jruby instance so that interacts with the
;; Coordinator, so that we have fine-grained control over how we handle the
;; incoming requests
(schema/defn ^:always-validate coordinated-mock-jruby-instance
  [coordinator :- (schema/protocol coordinator/TaskCoordinator)
   config :- {schema/Keyword schema/Any}]
  (let [puppet-config (jruby-testutils/mock-puppet-config-settings
                       (:jruby-puppet config))]
    (reify JRubyPuppet
      (getSetting [_ setting]
        (get puppet-config setting))
      (handleRequest [this request]
       ;; read the request-id from the query params so that we can
       ;; interact with the test coordinator
        (let [request-id (get-in request ["params" "request-id"])]
          ;; notify the coordinator that we've borrowed a jruby instance
          (coordinator/notify-task-progress coordinator request-id :borrowed-jruby)
          ;; if the request has a 'sleep' query param, sleep
          (when-let [sleep (get-in request ["params" "sleep"])]
            (log/debugf "JRuby handler: request '%s' sleeping '%s'" request-id sleep)
            (Thread/sleep (Long/parseLong sleep)))
          ;; notify coordinator that we're about to return the jruby to the pool
          (coordinator/notify-task-progress coordinator request-id :returning-jruby)
          (JRubyPuppetResponse. (int 200) "hi!" "text/plain" "9.0.0.0")))
      (puppetVersion [_]
        "1.2.3")
      (terminate [_]
        (log/info "Terminating Master")))))

(def TestEnvironment
  {:metrics jruby-metrics-core/JRubyMetrics
   :sample-metrics! IFn
   :sampling-scheduled? Atom
   :coordinator (schema/protocol coordinator/TaskCoordinator)
   :expected-metrics-values IFn
   :update-expected-values IFn
   :current-metrics-values IFn
   :jruby-service (schema/protocol jruby-protocol/JRubyPuppetService)})

(schema/defn ^:always-validate build-test-env :- TestEnvironment
  [sampling-scheduled? :- Atom
   coordinator :- (schema/protocol coordinator/TaskCoordinator)
   app]
  (let [jruby-metrics-service (tk-app/get-service app :JRubyMetricsService)
        {:keys [num-jrubies num-free-jrubies requested-count borrow-count
                borrow-timeout-count borrow-retry-count return-count
                borrowed-instances requested-instances]
         :as metrics} (jruby-metrics-protocol/get-metrics
                                                   jruby-metrics-service)
        jruby-service (tk-app/get-service app :JRubyPuppetService)
        sample-metrics! #(jruby-metrics-core/sample-jruby-metrics! jruby-service metrics)

        ;; an atom to track the expected values for the basic metrics, so
        ;; that we don't have to keep track of the latest counts by hand
        ;; in all of the tests
        expected-values-atom (atom {:num-jrubies 2
                                    :num-free-jrubies 2
                                    :requested-count 0
                                    :borrow-count 0
                                    :borrow-timeout-count 0
                                    :borrow-retry-count 0
                                    :return-count 0
                                    :current-requested-instances 0
                                    :current-borrowed-instances 0})

        ;; convenience functions to use for comparing current metrics
        ;; values with expected values.
        current-metrics-values (fn [] {:num-jrubies (.getValue num-jrubies)
                                       :num-free-jrubies (.getValue num-free-jrubies)
                                       :requested-count (.getCount requested-count)
                                       :borrow-count (.getCount borrow-count)
                                       :borrow-timeout-count (.getCount borrow-timeout-count)
                                       :borrow-retry-count (.getCount borrow-retry-count)
                                       :return-count (.getCount return-count)
                                       :current-requested-instances (count @requested-instances)
                                       :current-borrowed-instances (count @borrowed-instances)})
        update-expected-values (fn [deltas]
                                 (reset! expected-values-atom
                                   (reduce (fn [acc [k delta]]
                                             (update-in acc [k] + delta))
                                     @expected-values-atom
                                     deltas)))
        expected-metrics-values (fn [] @expected-values-atom)]
    {:metrics metrics
     :sample-metrics! sample-metrics!
     :sampling-scheduled? sampling-scheduled?
     :coordinator coordinator
     :expected-metrics-values expected-metrics-values
     :update-expected-values update-expected-values
     :current-metrics-values current-metrics-values
     :jruby-service jruby-service}))

(defmacro with-metrics-test-env
  [test-env-var-name config & body]
  `(let [coordinator# (coordinator/task-coordinator request-phases)
        ;; we will stub out the call that would normal schedule a recurring
        ;; job to take samples of the metrics, so that we have control over
        ;; when the sampling occurs.  Otherwise these tests would be very racy.
        sampling-scheduled?# (atom false)
        mock-schedule-metrics-sampler# (fn [_# _# _#] (reset! sampling-scheduled?# true))]
    (with-redefs [puppetlabs.services.jruby.jruby-metrics-core/schedule-metrics-sampler! mock-schedule-metrics-sampler#
                  puppetlabs.trapperkeeper.services.protocols.scheduler/stop-job (constantly true)]
      (bootstrap/with-app-with-config
        app#
        (jruby-testutils/add-mock-jruby-pool-manager-service
         (conj bootstrap-testutils/services-from-dev-bootstrap
               (comidi-handler-service coordinator#))
         ~config
         (partial coordinated-mock-jruby-instance coordinator#))
        ~config
        (let [~test-env-var-name (build-test-env sampling-scheduled?# coordinator# app#)]
          ~@body)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:metrics basic-metrics-test
  (with-metrics-test-env test-env default-test-config
    (let [{:keys [num-jrubies num-free-jrubies requested-count
                  requested-jrubies-histo borrow-count borrow-timeout-count
                  borrow-retry-count return-count free-jrubies-histo
                  borrow-timer wait-timer requested-instances
                  borrowed-instances]} (:metrics test-env)
          {:keys [update-expected-values current-metrics-values
                  expected-metrics-values coordinator]} test-env]
      (testing "initial metrics values"
        (is (= 2 (.getValue num-jrubies)))
        (is (= 2 (.getValue num-free-jrubies)))
        (is (= 0 (.getCount requested-count)))
        (is (= 0.0 (metrics/mean requested-jrubies-histo)))
        (is (= 0 (.getCount borrow-count)))
        (is (= 0 (.getCount borrow-timeout-count)))
        (is (= 0 (.getCount borrow-retry-count)))
        (is (= 0 (.getCount return-count)))
        (is (= 0.0 (metrics/mean free-jrubies-histo)))
        (is (= 0 (metrics/mean-millis borrow-timer)))
        (is (= 0 (metrics/mean-millis wait-timer)))
        (is (= 0 (count @requested-instances)))
        (is (= 0 (count @borrowed-instances))))

      (testing "basic metrics values: happy-path"
        (sync-request coordinator 1 "/foo/bar/req1")
        (sync-request coordinator 2 "/foo/baz/req2")

        (update-expected-values {:requested-count 2
                                 :borrow-count 2
                                 :return-count 2})

        (let [expected-metrics (expected-metrics-values)]
          (is (= expected-metrics (current-metrics-values)))
          (is (= expected-metrics (-> (current-jruby-status-metrics)
                                      (jruby-status-metric-counters)))))))))

(deftest ^:metrics borrowed-instances-test
  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator update-expected-values expected-metrics-values
                  current-metrics-values jruby-service]} test-env
          {:keys [borrowed-instances]} (:metrics test-env)
          start-time (System/currentTimeMillis)]
      (testing "introspect reasons for borrowed instances"
        (async-request coordinator 1 "/foo/bar/async1" :returning-jruby)
        (async-request coordinator 2 "/foo/baz/async2" :returning-jruby)

        ;; when we get here we should have two active requests blocked, so
        ;; we can check out some of those metrics.
        (update-expected-values {:num-free-jrubies -2
                                 :requested-count 2
                                 :borrow-count 2
                                 :current-borrowed-instances 2})

        (let [expected-metrics (expected-metrics-values)
              jruby-status-metrics (current-jruby-status-metrics)]
          (is (= expected-metrics (current-metrics-values)))
          (is (= expected-metrics (-> (current-jruby-status-metrics)
                                      (jruby-status-metric-counters))))

          ;; let's take a peek at the info about the borrowed instances.
          ;; the keys for `borrowed-instances` are the jruby instance ids.
          (is (= #{1 2} (set (keys @borrowed-instances))))
          (let [expected-uris #{"/foo/bar/async1" "/foo/baz/async2"}
                actual-uris #(set (map (fn [instance]
                                         (get-in instance
                                                 [:reason :request :uri])) %))]
            (is (= expected-uris (actual-uris (vals @borrowed-instances))))
            (is (= expected-uris (actual-uris (:borrowed-instances
                                               jruby-status-metrics)))))

          (let [expected-routes #{"foo-baz-:baz" "foo-bar-:bar"}
                actual-routes #(set (map (fn [instance]
                                           (get-in instance %1)) %2))]
            (is (= expected-routes (actual-routes
                                    [:reason :request :route-info :route-id]
                                    (vals @borrowed-instances))))
            (is (= expected-routes (actual-routes
                                    [:reason :request :route-id]
                                    (:borrowed-instances jruby-status-metrics)))))

          (doseq [borrowed (vals @borrowed-instances)]
            (is (= :get (get-in borrowed [:reason :request :request-method])))
            (is (timestamp-after? start-time (:time borrowed))))

          (doseq [borrowed (:borrowed-instances jruby-status-metrics)]
            (is (= "get" (get-in borrowed [:reason :request :request-method])))
            (is (timestamp-after? start-time (:time borrowed)))))

        ;; unblock both of our requests
        (coordinator/final-result coordinator 1)
        (coordinator/final-result coordinator 2)

        ;; validate that the metrics are updated after the unblocks
        (update-expected-values {:num-free-jrubies 2
                                 :return-count 2
                                 :current-borrowed-instances -2})
        (is (= (expected-metrics-values) (current-metrics-values))))

      (testing "introspect borrows that did not come from an http request"
        ;; borrow an instance manually (rather than via an HTTP request)
        (let [instance (jruby-testutils/borrow-instance jruby-service :metrics-manual-borrow-test)]
          (update-expected-values {:num-free-jrubies -1
                                   :requested-count 1
                                   :borrow-count 1
                                   :current-borrowed-instances 1})
          (is (= (expected-metrics-values) (current-metrics-values)))

          ;; validate that the metrics show info about the borrowed instance
          (let [borrowed (first (vals @borrowed-instances))]
            (is (timestamp-after? start-time (:time borrowed)))
            (is (= :metrics-manual-borrow-test (:reason borrowed))))

          (jruby-testutils/return-instance jruby-service instance :metrics-manual-borrow-test))

        (update-expected-values {:num-free-jrubies 1
                                 :return-count 1
                                 :current-borrowed-instances -1})
        (is (= (expected-metrics-values) (current-metrics-values)))))))

(deftest ^:metrics requested-instances-test
  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator update-expected-values expected-metrics-values
                  current-metrics-values jruby-service]} test-env
          {:keys [requested-count requested-instances borrowed-instances]} (:metrics test-env)
          start-time (System/currentTimeMillis)]
      (testing "introspect reasons for requested instances"
        ;; this test is about validating that requests that are stuck
        ;; waiting for a jruby instance from an empty pool show up in
        ;; the metrics.
        ;;
        ;; first we'll queue up a few requests to consume the 2 jruby instances
        (async-request coordinator 1 "/foo/bar/async1" :returning-jruby)
        (async-request coordinator 2 "/foo/baz/async2" :returning-jruby)

        (update-expected-values {:num-free-jrubies -2
                                 :requested-count 2
                                 :borrow-count 2
                                 :current-borrowed-instances 2})

        (let [expected-metrics (expected-metrics-values)
              jruby-status-metrics (current-jruby-status-metrics)]
          (is (= expected-metrics (current-metrics-values)))
          (is (= expected-metrics (-> (current-jruby-status-metrics)
                                      (jruby-status-metric-counters)))))

        ;; now we'll create a few more requests and tell them they may
        ;; try to proceed to the :borrowed-jruby phase; they won't
        ;; be able to get there, because the pool is empty, so we won't
        ;; block waiting for them.
        (async-request coordinator 3 "/foo/bar/async3")
        (coordinator/unblock-task-to coordinator 3 :borrowed-jruby)
        (async-request coordinator 4 "/foo/baz/async4")
        (coordinator/unblock-task-to coordinator 4 :borrowed-jruby)

        ;; wait for requests 3 and 4 to progress to their attempts to
        ;; borrow jrubies
        (let [expected-requested-count (+ 2 (:requested-count (expected-metrics-values)))]
          (while (> expected-requested-count (.getCount requested-count))))

        (update-expected-values {:requested-count 2
                                 :current-requested-instances 2})

        (let [expected-metrics (expected-metrics-values)
              jruby-status-metrics (current-jruby-status-metrics)]
          (is (= expected-metrics (current-metrics-values)))
          (is (= expected-metrics (-> (current-jruby-status-metrics)
                                      (jruby-status-metric-counters))))

          ;; now, make sure we can see info about requests 3 and 4 in the
          ;; metrics
          (let [expected-uris #{"/foo/bar/async3" "/foo/baz/async4"}
                actual-uris #(set (map (fn [instance]
                                         (get-in instance
                                                 [:reason :request :uri])) %))]
            (is (= expected-uris (actual-uris (vals @requested-instances)))))

          (let [expected-routes #{"foo-baz-:baz" "foo-bar-:bar"}
                actual-routes #(set (map (fn [instance]
                                           (get-in instance %1)) %2))]
            (is (= expected-routes (actual-routes
                                    [:reason :request :route-info :route-id]
                                    (vals @requested-instances))))
            (is (= expected-routes (actual-routes
                                    [:reason :request :route-id]
                                    (:requested-instances jruby-status-metrics)))))

          (doseq [borrowed (vals @requested-instances)]
            (is (= :get (get-in borrowed [:reason :request :request-method])))
            (is (timestamp-after? start-time (:time borrowed))))

          (doseq [borrowed (:requested-instances jruby-status-metrics)]
            (is (= "get" (get-in borrowed [:reason :request :request-method])))
            (is (timestamp-after? start-time (:time borrowed)))))

        ;; finish the first two requests
        (coordinator/final-result coordinator 1)
        (coordinator/final-result coordinator 2)

        ;; make sure requests 3 and 4 have successfully borrowed
        (coordinator/wait-for-task coordinator 3 :borrowed-jruby)
        (coordinator/wait-for-task coordinator 4 :borrowed-jruby)

        (update-expected-values {:return-count 2
                                 :borrow-count 2
                                 :current-requested-instances -2})
        (is (= (expected-metrics-values) (current-metrics-values)))

        ;; finish 3 and 4
        (coordinator/final-result coordinator 3)
        (coordinator/final-result coordinator 4)

        (update-expected-values {:num-free-jrubies 2
                                 :return-count 2
                                 :current-borrowed-instances -2})
        (is (= (expected-metrics-values) (current-metrics-values))))


      (testing "introspect requested instances that did not come from an http request"
        ;; this test is about validating that borrow attempts, which
        ;; did not originate from an HTTP request, but are stuck
        ;; waiting for a jruby instance from an empty pool show up in
        ;; the metrics.

        ;; first we'll queue up a few requests to consume the 2 jruby instances
        (async-request coordinator 1 "/foo/bar/async1" :borrowed-jruby)
        (async-request coordinator 2 "/foo/baz/async2" :borrowed-jruby)

        (update-expected-values {:num-free-jrubies -2
                                 :requested-count 2
                                 :borrow-count 2
                                 :current-borrowed-instances 2})
        (is (= (expected-metrics-values) (current-metrics-values)))

        ;; manually borrow an instance, but do it on another thread because
        ;; we know it will block right now due to the empty pool
        (let [future-instance (promise)
              wait-to-return (promise)
              future-thread (future
                             (let [instance (jruby-testutils/borrow-instance
                                             jruby-service :metrics-manual-borrow-test2)]
                                (deliver future-instance instance)
                                @wait-to-return
                                (jruby-testutils/return-instance
                                 jruby-service instance
                                 :metrics-manual-borrow-test2)))
              expected-request-count (inc (:requested-count (expected-metrics-values)))]
          ;; wait for manual borrow attempt to register as a requested instance
          (while (> expected-request-count (.getCount requested-count)))

          (update-expected-values {:requested-count 1
                                   :current-requested-instances 1})
          (is (= (expected-metrics-values) (current-metrics-values)))

          ;; validate that we can see it in the metrics
          (let [requested (first (vals @requested-instances))]
            (is (timestamp-after? start-time (:time requested)))
            (is (= :metrics-manual-borrow-test2 (:reason requested))))

          (coordinator/final-result coordinator 1)
          (coordinator/final-result coordinator 2)

          (let [instance @future-instance]
            (update-expected-values {:num-free-jrubies 1
                                     :return-count 2
                                     :borrow-count 1
                                     :current-borrowed-instances -1
                                     :current-requested-instances -1})
            (is (= (expected-metrics-values) (current-metrics-values)))

            (let [borrowed (first (vals @borrowed-instances))]
              (is (timestamp-after? start-time (:time borrowed)))
              (is (= :metrics-manual-borrow-test2 (:reason borrowed))))

            (deliver wait-to-return true)
            @future-thread))

        (update-expected-values {:num-free-jrubies 1
                                 :return-count 1
                                 :current-borrowed-instances -1})
        (is (= (expected-metrics-values) (current-metrics-values)))))))

(deftest ^:metrics timers-test
  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator sampling-scheduled? sample-metrics!
                  expected-metrics-values update-expected-values
                  current-metrics-values]} test-env
          {:keys [requested-jrubies-histo free-jrubies-histo
                  borrow-timer wait-timer requested-count
                  num-free-jrubies]} (:metrics test-env)]
      (testing "borrow/wait timers, histograms and sampling"
        ;; under normal circumstances, we'd be taking samples for the
        ;; histogram metrics at a scheduled interval.  However, for the
        ;; purposes of testing, we mocked that out so that we can
        ;; trigger the sampling explicitly.
        ;;
        ;; first we should double-check that the sampler *would* have
        ;; been scheduled if it hadn't been mocked out.
        (testing "metrics sampling is scheduled"
          (is (true? @sampling-scheduled?)))

        ;; now let's just validate that we know what our starting values
        ;; are.
        (is (= 0.0 (metrics/mean requested-jrubies-histo)))
        (is (= 0.0 (metrics/mean free-jrubies-histo)))
        (is (= 0 (metrics/mean-millis borrow-timer)))
        (is (= 0 (metrics/mean-millis wait-timer)))

        (testing "wait timer increases under load"
          ;; we're going to run a bunch of requests that sleep for 10
          ;; millis each, and take a sample after we unblock each one.
          ;; the samples should all be increasing.
          (let [samples (atom [])
                done? (promise)
                create-requests (fn []
                                  (doseq [request-id (range 10)]
                                    (async-request coordinator request-id "/foo/bar/foo?sleep=10")
                                    ;; allow each request to proceed as far as borrowing
                                    ;; a jruby, but don't wait for them.  (most
                                    ;; of them will be blocked because the pool
                                    ;; will be empty.)
                                    (coordinator/unblock-task-to coordinator request-id :borrowed-jruby)))

                run-request-and-take-sample (fn [request-id phase]
                                              ;; block until the request has borrowed a jruby; we need
                                              ;; to do this since we told them they were unblocked to
                                              ;; that point.
                                              (coordinator/wait-for-task coordinator request-id :borrowed-jruby)
                                              (let [current-wait-time (metrics/mean-millis wait-timer)]
                                                ;; now we know the request is blocked at the :borrowed-jruby phase.
                                                ;; sleep for a few ms longer than the current wait time, to make sure
                                                ;; that the other queued requests show a noticeable difference in their
                                                ;; borrow time.
                                                (Thread/sleep (+ current-wait-time 10)))
                                              ;; and now we can advance it to the end.
                                              (coordinator/final-result coordinator request-id)
                                              ;; take a sample and add it to the samples atom
                                              (sample-metrics!)
                                              (swap! samples conj (metrics/mean-millis wait-timer))
                                              ;; check to see if all of the requests are done
                                              (when (= 10 (count @samples))
                                                (deliver done? true)))]
            (create-requests)

            ;; block until all of the requests have reached the point
            ;; where they either have a jruby or are blocked in an
            ;; attempt to take one from the empty pool.
            (let [expected-requested-count (+ 10 (:requested-count (expected-metrics-values)))]
              (while (> expected-requested-count (.getCount requested-count))))

            ;; add callbacks that get fired when each request reaches
            ;; the :borrowed-jruby phase
            (doseq [request-id (range 10)]
              (coordinator/callback-at-phase coordinator request-id :borrowed-jruby run-request-and-take-sample))

            ;; block until all requests are completed
            @done?

            ;; we drop the first two samples because those came from the
            ;; first two jrubies borrowed, so, should have had basically zero
            ;; wait time.  All subsequent requests will have
            ;; been queued and blocked waiting for a jruby together,
            ;; so the wait times should be increasing for each request.
            (let [relevant-samples (drop 2 @samples)]
              ;; To validate that the samples are all increasing, we can just
              ;; compare it to a sorted version of itself
              (is (= (sort relevant-samples) relevant-samples)))

            (update-expected-values {:requested-count 10
                                     :borrow-count 10
                                     :return-count 10})
            (is (= (expected-metrics-values) (current-metrics-values)))))

        (testing "free-jrubies histo increases when no instances are borrowed"
          ;; validate that all the jrubies are free
          (is (= 2 (.getValue num-free-jrubies)))
          (let [initial-value (metrics/mean free-jrubies-histo)]
            ;; take 10 samples; no jrubies are in use, so the average
            ;; should increase with each sample.
            (let [samples (for [i (range 10)]
                            (do
                              (sample-metrics!)
                              (metrics/mean free-jrubies-histo)))]
              (is (= (sort samples) samples)))
            (is (< initial-value (metrics/mean free-jrubies-histo))))

          (is (= (expected-metrics-values) (current-metrics-values))))

        (testing "requested-jrubies histo decreases when requests are not queued"
          (let [initial-value (metrics/mean requested-jrubies-histo)]
            ;; take 10 samples; the average should increase with each sample.
            (let [samples (for [i (range 10)]
                            (do
                              (sample-metrics!)
                              (metrics/mean requested-jrubies-histo)))]
              (is (= (reverse (sort samples)) samples)))

            (is (> initial-value (metrics/mean requested-jrubies-histo))))

          (is (= (expected-metrics-values) (current-metrics-values)))))))

  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator sample-metrics!
                  expected-metrics-values update-expected-values
                  current-metrics-values]} test-env
          {:keys [requested-jrubies-histo requested-count]} (:metrics test-env)]
      (testing "requested-jrubies histo increases when requests are queued"
        ;; take a sample to ensure that the requested-jrubies-histo is updated
        (sample-metrics!)

        (is (= 0.0 (metrics/mean requested-jrubies-histo)))

        ;; get ourselves to a state where two jrubies instance requests are queued
        (async-request coordinator 1 "/foo/bar/req" :borrowed-jruby)
        (async-request coordinator 2 "/foo/bar/req" :borrowed-jruby)
        ;; the next two requests won't be able to make it all the way to
        ;; ':borrowed-jruby', because the pool is empty, so we won't
        ;; block waiting for them to get there.
        (async-request coordinator 3 "/foo/bar/req")
        (coordinator/unblock-task-to coordinator 3 :borrowed-jruby)
        (async-request coordinator 4 "/foo/bar/req")
        (coordinator/unblock-task-to coordinator 4 :borrowed-jruby)

        ;; wait until all of the jrubies have been requested so we know
        ;; that requests 3 and 4 are blocked trying to take a jruby
        ;; from the pool
        (let [expected-requested-count (+ 4 (:requested-count (expected-metrics-values)))]
          (while (> expected-requested-count (.getCount requested-count))))

        ;; ok, now we have two jruby instance requests pending.
        ;; take some samples and validate that the histogram is
        ;; increasing.
        (let [initial-value (metrics/mean requested-jrubies-histo)]
          (let [samples (for [i (range 10)]
                          (do
                            (sample-metrics!)
                            (metrics/mean requested-jrubies-histo)))]
            (is (= (sort samples) samples)))

          (is (< initial-value (metrics/mean requested-jrubies-histo))))

        ;; finish up the requests
        (coordinator/final-result coordinator 1)
        (coordinator/final-result coordinator 2)
        (coordinator/wait-for-task coordinator 3 :borrowed-jruby)
        (coordinator/wait-for-task coordinator 4 :borrowed-jruby)
        (coordinator/final-result coordinator 3)
        (coordinator/final-result coordinator 4)

        (update-expected-values {:requested-count 4
                                 :borrow-count 4
                                 :return-count 4})
        (is (= (expected-metrics-values) (current-metrics-values))))))

  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator sample-metrics!
                  expected-metrics-values update-expected-values
                  current-metrics-values]} test-env
          {:keys [free-jrubies-histo num-free-jrubies]} (:metrics test-env)]
      (testing "free-jrubies histo decreases when instances are borrowed"
        ;; take a sample to ensure that the free-jrubies-histo is updated
        (sample-metrics!)

        (let [initial-value (metrics/mean free-jrubies-histo)]
          (is (= 2.0 initial-value))

          ;; borrow two instances so we know that there are no free jrubies
          (async-request coordinator 1 "/foo/bar/req" :borrowed-jruby)
          (async-request coordinator 2 "/foo/bar/req" :borrowed-jruby)
          (is (= 0 (.getValue num-free-jrubies)))

          ;; take 10 samples; the average should decrease with each sample.
          (let [samples (for [i (range 10)]
                          (do
                            (sample-metrics!)
                            (metrics/mean free-jrubies-histo)))]
            (is (= (reverse (sort samples)) samples)))

          (is (> initial-value (metrics/mean free-jrubies-histo)))

          ;; free up our blocked requests
          (coordinator/final-result coordinator 1)
          (coordinator/final-result coordinator 2))

        (update-expected-values {:requested-count 2
                                 :borrow-count 2
                                 :return-count 2})
        (is (= (expected-metrics-values) (current-metrics-values))))))

  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator sample-metrics!
                  expected-metrics-values update-expected-values
                  current-metrics-values]} test-env
          {:keys [wait-timer num-free-jrubies]} (:metrics test-env)]
      (testing "wait timer decreases when not under load"
        ;; set the wait timer to an artificially super-high value so that we
        ;; can be reasonably assured that subsequent borrows will reduce the mean
        (.update wait-timer 1 TimeUnit/HOURS)

        ;; make sure there are no jrubies in use, so we know that there
        ;; should be no wait times.
        (is (= 2 (.getValue num-free-jrubies)))

        (let [start-wait-time (metrics/mean-millis wait-timer)]
          (dotimes [request-id 10]
            (let [last-wait-time (metrics/mean-millis wait-timer)]
              ;; make a request (should have zero wait time)
              (sync-request coordinator request-id "/foo/bar/req")
              ;; take a sample and confirm that the mean wait time has decreased
              (sample-metrics!)
              (is (>= last-wait-time (metrics/mean-millis wait-timer)))))
          (is (> start-wait-time (metrics/mean-millis wait-timer))))

        (update-expected-values {:requested-count 10
                                 :borrow-count 10
                                 :return-count 10})
        (is (= (expected-metrics-values) (current-metrics-values))))))

  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator sample-metrics!
                  expected-metrics-values update-expected-values
                  current-metrics-values]} test-env
          {:keys [borrow-timer]} (:metrics test-env)]
      (testing "borrow timer increases when requests are slower"
        (dotimes [request-id 10]
          (let [last-borrow-time (metrics/mean-millis borrow-timer)]
            (let [longer-borrow-time (+ 50 last-borrow-time)]
              (sync-request coordinator request-id (format "/foo/bar/req?sleep=%s" longer-borrow-time))
              ;; sample and validate that the borrow time has increased
              (sample-metrics!)
              (let [new-borrow-time (metrics/mean-millis borrow-timer)]
                (is (<= last-borrow-time new-borrow-time)
                    (format
                     (str "Borrow time did not increase! "
                          "last-borrow-time: '%s', longer-borrow-time: '%s', "
                          "new-borrow-time: '%s'")
                     last-borrow-time longer-borrow-time new-borrow-time))))))

          (update-expected-values {:requested-count 10
                                   :borrow-count 10
                                   :return-count 10})
          (is (= (expected-metrics-values) (current-metrics-values))))))

  (with-metrics-test-env test-env default-test-config
    (let [{:keys [coordinator sample-metrics!
                  expected-metrics-values update-expected-values
                  current-metrics-values]} test-env
          {:keys [borrow-timer]} (:metrics test-env)]
      (testing "borrow timer decreases when requests are faster"
        ;; set the borrow timer to an artificially super-high value so that we
        ;; can be reasonably assured that subsequent borrows will reduce the mean
        (.update borrow-timer 1 TimeUnit/HOURS)
        (let [start-borrow-time (metrics/mean-millis borrow-timer)]
          ;; make some requests with no sleep
          (dotimes [request-id 10]
            (let [last-borrow-time (metrics/mean-millis borrow-timer)]
              (sync-request coordinator request-id "/foo/bar/req")
              ;; sample and validate that the borrow time has decreased
              (sample-metrics!)
              (is (>= last-borrow-time (metrics/mean-millis borrow-timer)))))
          (is (> start-borrow-time (metrics/mean-millis borrow-timer))))

        (update-expected-values {:requested-count 10
                                 :borrow-count 10
                                 :return-count 10})
        (is (= (expected-metrics-values) (current-metrics-values)))))))

(deftest ^:metrics borrow-timeout-test
  (with-metrics-test-env
    test-env
    (assoc-in default-test-config
      ;;; set the borrow timeout to a low value so that we can test
      ;;; timeout handling without making the test too slow
      [:jruby-puppet :borrow-timeout] 1000)
    (let [{:keys [coordinator update-expected-values expected-metrics-values
                  current-metrics-values jruby-service]} test-env]
      (testing "borrow timeout"
        ;; first we'll queue up a few requests to consume the 2 jruby instances
        (async-request coordinator 1 "/foo/bar/async1" :returning-jruby)
        (async-request coordinator 2 "/foo/baz/async2" :returning-jruby)

        (update-expected-values {:num-free-jrubies -2
                                 :requested-count 2
                                 :borrow-count 2
                                 :current-borrowed-instances 2})
        (is (= (expected-metrics-values) (current-metrics-values)))

        ;; now attempt a manual borrow and allow it to timeout
        (let [borrow-result (jruby-testutils/borrow-instance jruby-service :introspect-manual-borrow-test2)]
          (is (nil? borrow-result)))

        (update-expected-values {:requested-count 1
                                 :borrow-timeout-count 1})
        (is (= (expected-metrics-values) (current-metrics-values)))

        (coordinator/final-result coordinator 1)
        (coordinator/final-result coordinator 2)

        (update-expected-values {:num-free-jrubies 2
                                 :return-count 2
                                 :current-borrowed-instances -2})
        (is (= (expected-metrics-values) (current-metrics-values)))))))

(deftest request-queue-limit
  (with-metrics-test-env
    test-env
    (-> default-test-config
        (assoc-in [:jruby-puppet :max-active-instances] 2)
        (assoc-in [:jruby-puppet :max-queued-requests] 2))
    (let [{:keys [current-metrics-values coordinator]} test-env
          {:keys [requested-count requested-instances
                  borrowed-instances queue-limit-hit-meter]} (:metrics test-env)]
      (testing "denies requests when rate limit hit"
        ;; Block up two JRuby instances
        (async-request coordinator 1 "/foo/bar/async1" :returning-jruby)
        (async-request coordinator 2 "/foo/bar/async2" :returning-jruby)

        ;; Create two pending requests
        (async-request coordinator 3 "/foo/bar/async3")
        (async-request coordinator 4 "/foo/bar/async4")
        (coordinator/unblock-task-to coordinator 3 :borrowed-jruby)
        (coordinator/unblock-task-to coordinator 4 :borrowed-jruby)

        ; Wait for async requests to hit metrics.
        (while (> 4 (.getCount requested-count)))

        (logging/with-test-logging
          (let [resp        (http-get "http://127.0.0.1:8140/foo/uncoord/sync1")
                status-code (:status resp)
                retry-after (-> resp
                                (get-in [:headers "retry-after"])
                                Integer/parseInt)]
            (is (= 503 status-code))
            (is (<= 0 retry-after 1800))
            (is (logged?
                 #"The number of requests waiting for a JRuby instance has exceeded the limit"
                 :error))))

        ;; unblock all requests
        (doseq [i (range 1 5)]
          (coordinator/final-result coordinator i))

        ;; Assert that one instance of the rate limit being applied was
        ;; recorded to metrics.
        (is (= 1 (.getCount queue-limit-hit-meter)))))))
