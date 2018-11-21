(ns puppetlabs.services.master.master-service-test
  (:require
    [clojure.test :refer :all]
    [clojure.set :as setutils]
    [puppetlabs.services.master.master-service :refer :all]
    [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap-testutils]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [me.raynes.fs :as fs]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.http.client.sync :as http-client]
    [cheshire.core :as json]
    [puppetlabs.services.jruby.jruby-puppet-service :as jruby-service]
    [puppetlabs.trapperkeeper.services :as tk-services]
    [puppetlabs.puppetserver.testutils :as testutils]
    [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]
    [schema.core :as schema]
    [puppetlabs.services.master.master-core :as master-core]
    [puppetlabs.services.puppet-profiler.puppet-profiler-core :as puppet-profiler-core]
    [puppetlabs.services.protocols.puppet-profiler :as profiler-protocol]
    [puppetlabs.trapperkeeper.services.metrics.metrics-core :as metrics-core]
    [puppetlabs.trapperkeeper.services.metrics.metrics-testutils :as metrics-testutils]
    [puppetlabs.trapperkeeper.services :as tk]
    [clojure.string :as str]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [schema.test :as schema-test]))

(def test-resources-path "./dev-resources/puppetlabs/services/master/master_service_test")
(def test-resources-code-dir (str test-resources-path "/codedir"))
(def test-resources-conf-dir (str test-resources-path "/confdir"))

(def master-service-test-runtime-dir "target/master-service-test")

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(use-fixtures :once
              schema-test/validate-schemas
              (fn [f]
                (testutils/with-config-dirs
                 {test-resources-conf-dir
                  master-service-test-runtime-dir}
                 (f))))

(defn http-get
  [url]
  (let [master-service-test-runtime-ssl-dir
        (str master-service-test-runtime-dir "/ssl")]
    (http-client/get (str "https://localhost:8140" url)
                     {:ssl-cert (str master-service-test-runtime-ssl-dir
                                     "/certs/localhost.pem")
                      :ssl-key (str master-service-test-runtime-ssl-dir
                                    "/private_keys/localhost.pem")
                      :ssl-ca-cert (str master-service-test-runtime-ssl-dir
                                        "/certs/ca.pem")
                      :headers {"Accept" "application/json"}
                      :as :text})))

(defn http-put
  [url body]
  (let [master-service-test-runtime-ssl-dir
        (str master-service-test-runtime-dir "/ssl")]
    (http-client/put (str "https://localhost:8140" url)
                     {:ssl-cert (str master-service-test-runtime-ssl-dir
                                     "/certs/localhost.pem")
                      :ssl-key (str master-service-test-runtime-ssl-dir
                                    "/private_keys/localhost.pem")
                      :ssl-ca-cert (str master-service-test-runtime-ssl-dir
                                        "/certs/ca.pem")
                      :body body
                      :headers {"Accept" "application/json"
                                "Content-type" "application/json"}
                      :as :text})))

(deftest ^:integration master-service-http-metrics
  (testing "HTTP metrics computed via use of the master service are correct"
    (bootstrap-testutils/with-puppetserver-running
     app
     {:jruby-puppet {:gem-path gem-path
                     :max-active-instances 1
                     :master-code-dir test-resources-code-dir
                     :master-conf-dir master-service-test-runtime-dir}
      :metrics {:server-id "localhost"}}
     ;; mostly just making sure we can get here w/o exception
     (is (true? true))

     ;; validate a few of the http metrics as long as we have the server up
     ;; anyway :)
     (let [master-service (tk-app/get-service app :MasterService)
           svc-context (tk-services/service-context master-service)
           http-metrics (:http-metrics svc-context)
           profiler-service (tk-app/get-service app :PuppetProfilerService)
           puppet-profiler (profiler-protocol/get-profiler profiler-service)]

       (logutils/with-test-logging
        (let [node-response (logutils/with-test-logging
                             (http-get "/puppet/v3/node/foo?environment=production"))
              node-response-body (-> node-response :body json/parse-string)]
          (is (= 200 (:status node-response)))
          ;; Assert that some of the content looks like it came from the
          ;; node endpoint
          (is (= "foo" (get node-response-body "name")))
          (is (= "production" (get node-response-body "environment"))))
        (let [catalog-response (logutils/with-test-logging
                                (http-get "/puppet/v3/catalog/foo?environment=production"))
              catalog-response-body (-> catalog-response :body json/parse-string)]
          (is (= 200 (:status catalog-response)))
          ;; Assert that some of the content looks like it came from the
          ;; catalog endpoint
          (is (testutils/catalog-contains? catalog-response-body
                                           "Class"
                                           "Foo"))

          (is (= "foo" (get catalog-response-body "name")))
          (is (= "production" (get catalog-response-body "environment"))))
        (is (= 404 (:status (http-get
                             "/puppet/funky/town")))))

       (is (= 3 (-> http-metrics :total-timer .getCount)))
       (is (= 1 (-> http-metrics :route-timers :other .getCount)))

       (is (= 1 (-> http-metrics :route-timers
                    (get "puppet-v3-node-/*/")
                    .getCount)))
       (is (= 1 (-> http-metrics :route-timers
                    (get "puppet-v3-catalog-/*/")
                    .getCount)))
       (is (= 0 (-> http-metrics :route-timers
                    (get "puppet-v3-report-/*/")
                    .getCount)))
       (testing "Catalog compilation increments catalog metrics and adds timing data"
         (let [profiler-status (puppet-profiler-core/v1-status puppet-profiler :debug)
               catalog-metrics (get-in profiler-status [:status :experimental :catalog-metrics])
               metric-names (map :metric catalog-metrics)
               expected-metrics #{"compile" "find_node"}]
           ;; NOTE: If this test fails, then likely someone changed a metric
           ;; name passed to Puppet::Util::Profiler.profile over in the Puppet
           ;; project without realizing that is a breaking change to metrics
           ;; critical for measuring compiler performance.
           (is (setutils/subset? expected-metrics (set metric-names)))
           (doseq [metric-name expected-metrics
                   :let [metric (first (filter #(= metric-name (:metric %))
                                               catalog-metrics))]]
             (is (= 1 (metric :count)))
             (is (= (metric :aggregate) (metric :mean)))))))

     (let [resp (http-get "/status/v1/services?level=debug")]
       (is (= 200 (:status resp)))
       (let [status (json/parse-string (:body resp) true)]
         (is (= #{:ca :jruby-metrics :master :puppet-profiler :status-service}
                (set (keys status))))

         (is (= 1 (get-in status [:jruby-metrics :service_status_version])))
         (is (= "running" (get-in status [:jruby-metrics :state])))
         (is (nil? (schema/check jruby-metrics-core/JRubyMetricsStatusV1
                                 (get-in status [:jruby-metrics :status]))))

         (is (= jruby-metrics-core/jruby-pool-lock-not-in-use
                (get-in status [:jruby-metrics :status :experimental
                                :jruby-pool-lock-status :current-state])))

         (is (= 1 (get-in status [:master :service_status_version])))
         (is (= "running" (get-in status [:master :state])))
         (is (nil? (schema/check master-core/MasterStatusV1
                                 (get-in status [:master :status]))))
         (testing "HTTP metrics in status endpoint are sorted in order of aggregate amount of time spent"
           (let [hit-routes #{"total" "puppet-v3-node-/*/"
                              "puppet-v3-catalog-/*/" "other"}
                 http-metrics (get-in status [:master :status :experimental :http-metrics])]
             (testing "'total' should come first since it is the sum of the other endpoints"
               (is (= "total" (:route-id (first http-metrics)))))
             (testing "The other two routes that actually received requests should come next"
               (is (= #{"puppet-v3-node-/*/" "puppet-v3-catalog-/*/"}
                      (set (map :route-id (rest (take 3 http-metrics)))))))
             (testing "The aggregate times should be in descending order"
               (let [aggregate-times (map :aggregate http-metrics)]
                 (= aggregate-times (reverse (sort aggregate-times)))))
             (testing "The counts should be accurate for the endpoints that we hit"
               (let [find-route (fn [route-metrics route-id]
                                  (first (filter #(= (:route-id %) route-id) route-metrics)))]
                 (is (= 3 (:count (find-route http-metrics "total"))))
                 (is (= 1 (:count (find-route http-metrics "puppet-v3-node-/*/"))))
                 (is (= 1 (:count (find-route http-metrics "puppet-v3-catalog-/*/"))))
                 (is (= 1 (:count (find-route http-metrics "other"))))))
             (testing "The counts should be zero for endpoints that we didn't hit"
               (is (every? #(= 0 %) (map :count
                                         (filter
                                          #(not (hit-routes (:route-id %)))
                                          http-metrics)))))))

         (is (= 1 (get-in status [:puppet-profiler :service_status_version])))
         (is (= "running" (get-in status [:puppet-profiler :state])))
         (is (nil? (schema/check puppet-profiler-core/PuppetProfilerStatusV1
                                 (get-in status [:puppet-profiler :status]))))
         (let [function-metrics (get-in status [:puppet-profiler :status :experimental :function-metrics])
               function-names (set (mapv :function function-metrics))]
           (is (contains? function-names "digest"))
           (is (contains? function-names "include")))

         (let [resource-metrics (get-in status [:puppet-profiler :status :experimental :resource-metrics])
               resource-names (set (mapv :resource resource-metrics))]
           (is (contains? resource-names "Class[Foo]"))
           (is (contains? resource-names "Class[Foo::Params]"))
           (is (contains? resource-names "Class[Foo::Bar]"))))))))

(deftest ^:integration master-service-jruby-metrics
  (testing "JRuby metrics computed via use of the master service actions are correct"
    (bootstrap-testutils/with-puppetserver-running
     app
     {:jruby-puppet {:gem-path gem-path
                     :max-active-instances 1
                     :master-code-dir test-resources-code-dir
                     :master-conf-dir master-service-test-runtime-dir}
      :metrics {:server-id "localhost"}}
     (let [jruby-metrics-service (tk-app/get-service app :JRubyMetricsService)
           svc-context (tk-services/service-context jruby-metrics-service)
           jruby-metrics (:metrics svc-context)
           jruby-service (tk-app/get-service app :JRubyPuppetService)
           time-before-first-borrow (System/currentTimeMillis)]
       ;; Use with-jruby-puppet to borrow the lone jruby out of the pool,
       ;; which should cause a subsequent catalog request to block on a borrow
       ;; request
       (jruby-service/with-jruby-puppet
        _
        jruby-service
        :master-service-metrics-test
        (let [time-before-second-borrow (System/currentTimeMillis)]
          (future
           (logutils/with-test-logging
            (http-get "/puppet/v3/catalog/localhost?environment=production")))
          ;; Wait up to 10 seconds for the catalog request to get to the
          ;; point where it is in the jruby borrow queue.
          (while (and
                  (< (- (System/currentTimeMillis) time-before-second-borrow)
                     10000)
                  (nil? (-> jruby-metrics
                            :requested-instances
                            deref
                            first
                            (get-in [:reason :request :uri]))))
            (Thread/yield))
          (let [resp (http-get "/status/v1/services/jruby-metrics?level=debug")]
            (is (= 200 (:status resp)))
            (let [status (json/parse-string (:body resp) true)]

              (is (= 1 (:service_status_version status)))
              (is (= "running" (:state status)))

              (testing "Info for borrowed instance is correct"
                (let [borrowed-instances (get-in status [:status
                                                         :experimental
                                                         :metrics
                                                         :borrowed-instances])
                      borrowed-instance (first borrowed-instances)]
                  (is (= 1 (count borrowed-instances)))
                  (is (= "master-service-metrics-test"
                         (:reason borrowed-instance)))
                  (is (>= (:time borrowed-instance) time-before-first-borrow))
                  (is (> (:duration-millis borrowed-instance) 0))
                  (is (>= (System/currentTimeMillis)
                          (+ (:duration-millis borrowed-instance)
                             (:time borrowed-instance))))))

              (testing "Info for requested instance is correct"
                (let [requested-instances (get-in status [:status
                                                          :experimental
                                                          :metrics
                                                          :requested-instances])
                      requested-instance (first requested-instances)]
                  (is (= 1 (count requested-instances)))
                  (is (= {:request
                          {:request-method "get"
                           :route-id "puppet-v3-catalog-/*/"
                           :uri "/puppet/v3/catalog/localhost"}}
                         (:reason requested-instance)))
                  (is (>= (:time requested-instance) time-before-second-borrow))
                  (is (> (:duration-millis requested-instance) 0))
                  (is (>= (System/currentTimeMillis)
                          (+ (:duration-millis requested-instance)
                             (:time requested-instance))))))))))))))

(deftest ^:integration ca-files-test
  (testing "CA settings from puppet are honored and the CA
            files are created when the service starts up"
    (let [ca-files-test-runtime-dir (str master-service-test-runtime-dir
                                         "/ca-files-test")
          ca-files-test-puppet-conf (fs/file test-resources-path
                                             "ca_files_test/puppet.conf")]
      (fs/delete-dir ca-files-test-runtime-dir)
      (testutils/with-puppet-conf-files
       {"puppet.conf" ca-files-test-puppet-conf}
       ca-files-test-runtime-dir
       (logutils/with-test-logging
        (bootstrap-testutils/with-puppetserver-running
         app
         {:jruby-puppet {:gem-path gem-path
                         :master-conf-dir ca-files-test-runtime-dir
                         :max-active-instances 1}
          :webserver {:port 8081}}
         (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
           (jruby/with-jruby-puppet
            jruby-puppet jruby-service :ca-files-test
            (letfn [(test-path!
                      [setting expected-path]
                      (is (= (ks/absolute-path expected-path)
                             (.getSetting jruby-puppet setting)))
                      (is (fs/exists? (ks/absolute-path expected-path))))]

              (test-path! "capub" (str ca-files-test-runtime-dir "/ca/ca_pub.pem"))
              (test-path! "cakey" (str ca-files-test-runtime-dir "/ca/ca_key.pem"))
              (test-path! "cacert" (str ca-files-test-runtime-dir "/ca/ca_crt.pem"))
              (test-path! "localcacert" (str ca-files-test-runtime-dir "/ca/ca.pem"))
              (test-path! "cacrl" (str ca-files-test-runtime-dir "/ca/ca_crl.pem"))
              (test-path! "hostcrl" (str ca-files-test-runtime-dir "/ca/crl.pem"))
              (test-path! "hostpubkey" (str ca-files-test-runtime-dir "/public_keys/localhost.pem"))
              (test-path! "hostprivkey" (str ca-files-test-runtime-dir "/private_keys/localhost.pem"))
              (test-path! "hostcert" (str ca-files-test-runtime-dir "/certs/localhost.pem"))
              (test-path! "serial" (str ca-files-test-runtime-dir "/certs/serial"))
              (test-path! "cert_inventory" (str ca-files-test-runtime-dir "/inventory.txt")))))))))))

(def graphite-enabled-config
  {:metrics {:server-id "localhost"
             :reporters {:graphite {:update-interval-seconds 5000
                                    :port 10001
                                    :host "foo.localdomain"}}
             :registries {:puppetserver
                          {:reporters {:graphite {:enabled true}}}}}
   :jruby-puppet {:gem-path gem-path
                  :master-code-dir test-resources-code-dir
                  :master-conf-dir master-service-test-runtime-dir}})

(defn get-puppetserver-registry-context
      [app]
      (-> app
          (tk-app/get-service :MetricsService)
          tk-services/service-context
          :registries
          deref
          :puppetserver))

(defn get-puppetserver-graphite-reporter
      [app]
      (:graphite-reporter (get-puppetserver-registry-context app)))

(deftest graphite-filtering-works
  (testing "default filter works"
    (let [reported-metrics-atom (atom {})]
      (with-redefs [metrics-core/build-graphite-sender
                    (fn [_ domain]
                      (metrics-testutils/make-graphite-sender reported-metrics-atom domain))]
        (logutils/with-test-logging
          (bootstrap-testutils/with-puppetserver-running
           app
           graphite-enabled-config
           (http-get "/puppet/v3/catalog/localhost?environment=production")
           (.report (get-puppetserver-graphite-reporter app))
           (testing "reports metrics on the default list"
             (is (metrics-testutils/reported? @reported-metrics-atom
                                              :puppetserver
                                              "puppetlabs.localhost.compiler.compile.mean")))
           (testing "doesn't report metrics not on the default list"
             (is (not (metrics-testutils/reported?
                       @reported-metrics-atom
                       :puppetserver
                       "puppetlabs.localhost.compiler.compile.production.mean")))))))))
  (testing "can add metrics to export to Graphite with the `metrics-allowed` setting"
      (let [reported-metrics-atom (atom {})]
        (with-redefs [metrics-core/build-graphite-sender
                      (fn [_ domain]
                        (metrics-testutils/make-graphite-sender reported-metrics-atom domain))]
          (logutils/with-test-logging
            (bootstrap-testutils/with-puppetserver-running
             app
             (assoc-in graphite-enabled-config [:metrics :registries :puppetserver :metrics-allowed]
                       ["compiler.compile.production"])
             (http-get "/puppet/v3/catalog/localhost?environment=production")
             (.report (get-puppetserver-graphite-reporter app))
             (testing "reports metrics on the default list"
               (is (metrics-testutils/reported?
                    @reported-metrics-atom
                    :puppetserver
                    "puppetlabs.localhost.compiler.compile.mean")))
             (testing "reports metrics on the metrics-allowed list"
               (is (metrics-testutils/reported?
                    @reported-metrics-atom
                    :puppetserver
                    "puppetlabs.localhost.compiler.compile.production.mean")))))))))

(deftest jvm-metrics-sent-to-graphite-test
  (let [reported-metrics-atom (atom {})]
    (with-redefs [metrics-core/build-graphite-sender
                  (fn [_ domain]
                    (metrics-testutils/make-graphite-sender reported-metrics-atom domain))]
      (bootstrap-testutils/with-puppetserver-running
       app
       graphite-enabled-config
       (let [registry (:registry (get-puppetserver-registry-context app))
             get-memory-map
             (fn [mem-type]
               (ks/mapvals #(.getValue %)
                           (filter #(.matches (key %) (format "puppetlabs.localhost.memory.%s.*" mem-type))
                                   (.getMetrics registry))))
             heap-memory-map (get-memory-map "heap")
             non-heap-memory-map (get-memory-map "non-heap")
             total-memory-map (get-memory-map "total")]
         (testing "heap memory metrics work"
           (= #{"puppetlabs.localhost.memory.heap.committed"
                "puppetlabs.localhost.memory.heap.init"
                "puppetlabs.localhost.memory.heap.max"
                "puppetlabs.localhost.memory.heap.used"} (ks/keyset heap-memory-map))
           (is (every? #(< 0 %) (vals heap-memory-map))))

         (testing "non-heap memory metrics work"
           (= #{"puppetlabs.localhost.memory.non-heap.committed"
                "puppetlabs.localhost.memory.non-heap.init"
                "puppetlabs.localhost.memory.non-heap.max"
                "puppetlabs.localhost.memory.non-heap.used"}
              (ks/keyset non-heap-memory-map))
           ;; Some of the memory metrics don't propagate correctly on OS X, which can result in a
           ;; value of -1. This is here so that these tests will pass when run in developers local
           ;; environments.
           (is (every? #(or (< 0 %) (= -1 %)) (vals non-heap-memory-map))))

         (testing "total memory metrics work"
           (= #{"puppetlabs.localhost.memory.total.committed"
                "puppetlabs.localhost.memory.total.init"
                "puppetlabs.localhost.memory.total.max"
                "puppetlabs.localhost.memory.total.used"}
              (ks/keyset total-memory-map))
           (is (every? #(< 0 %) (vals total-memory-map))))

         (testing "uptime metric works"
           (let [get-uptime (fn [] (-> registry
                                       .getMetrics
                                       (get "puppetlabs.localhost.uptime")
                                       .getValue))
                 uptime (get-uptime)]
             (is (< 0 uptime))
             ;; Make sure uptime can be updated after initialization.
             (Thread/sleep 1)
             (is (not= uptime (get-uptime))))))

       (.report (get-puppetserver-graphite-reporter app))

       (testing "jvm metrics are reported to graphite"
         (is (every? #(metrics-testutils/reported? @reported-metrics-atom
                                                   :puppetserver
                                                   %)
                     (map #(format "puppetlabs.localhost.memory.%s" %)
                          ["heap.committed"
                           "heap.init"
                           "heap.max"
                           "heap.used"
                           "non-heap.committed"
                           "non-heap.init"
                           "non-heap.max"
                           "non-heap.used"
                           "total.committed"
                           "total.init"
                           "total.max"
                           "total.used"])))

         (is (metrics-testutils/reported? @reported-metrics-atom
                                          :puppetserver
                                          "puppetlabs.localhost.uptime")))))))

(defn get-http-client-metrics-status
  []
  (-> "/status/v1/services/master?level=debug"
      http-get
      :body
      (json/parse-string true)
      (get-in [:status :experimental :http-client-metrics])))

(deftest ^:integration master-service-http-client-metrics
  (let [reported-metrics-atom (atom {})]
    (with-redefs [metrics-core/build-graphite-sender
                  (fn [_ domain]
                    (metrics-testutils/make-graphite-sender reported-metrics-atom domain))]
      (testing "HTTP client metrics are present in the master status"
        (bootstrap-testutils/with-puppetserver-running
         app
         graphite-enabled-config
         (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
               jruby-instance (jruby-testutils/borrow-instance jruby-service :http-client-metrics-test)
               container (:scripting-container jruby-instance)]
           (try
             (.runScriptlet
              container
              ;; create a client and assign it to a global variable
              (format "$c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                      8140 true))
             (let [make-request-with-metric-id (fn [metric-id-as-string]
                                                 (.runScriptlet
                                                  container
                                                  ;; So long as the server and port are accessible, it
                                                  ;; doesn't actually matter whether the endpoint is
                                                  ;; reachable or not - we just need to make requests
                                                  ;; with the given metric ids.
                                                  (format "$c.get('/fake', {}, {:metric_id => %s})"
                                                          metric-id-as-string)))]
               (testing "http-client-metrics key in master status"
                 (testing "empty array if no requests have been made"
                   (is (= [] (get-http-client-metrics-status))))

                 (testing "doesn't include metrics for metric-ids it is not supposed to have"
                   (make-request-with-metric-id "['fake', 'fake', 'fakery']")
                   (is (= [] (get-http-client-metrics-status))))

                 (testing "includes metrics for metric-ids it is supposed to have"
                   (make-request-with-metric-id "['puppetdb', 'query']")
                   (is (= [["puppetdb" "query"]] (map :metric-id (get-http-client-metrics-status)))))

                 (testing "only includes exact metric-ids it's supposed to have"
                   (make-request-with-metric-id "['puppetdb', 'command', 'replace_catalog', 'foonode']")
                   (let [metric-ids (map :metric-id (get-http-client-metrics-status))]
                     (is (= #{["puppetdb" "query"] ["puppetdb" "command" "replace_catalog"]}
                            (set metric-ids)))))

                 (testing "includes all metrics it is supposed to have"
                   (make-request-with-metric-id "['puppet', 'report', 'http']")
                   (make-request-with-metric-id "['puppetdb', 'command', 'replace_facts', 'foonode']")
                   (make-request-with-metric-id "['puppetdb', 'command', 'store_report', 'foonode']")
                   (make-request-with-metric-id "['puppetdb', 'facts', 'find', 'foonode']")
                   (make-request-with-metric-id "['puppetdb', 'facts', 'search']")
                   (make-request-with-metric-id "['puppetdb', 'resource', 'search', 'Package']")
                   (= (set master-core/puppet-server-http-client-metrics-for-status)
                      (set (map :metric-id (get-http-client-metrics-status)))))

                 (testing "all metrics contain information they are supposed to have"
                   (let [metrics (get-http-client-metrics-status)]
                     (testing "contains correct keys"
                       (is (every?
                            #(= #{:metric-id :metric-name :count :mean :aggregate} (set (keys %)))
                            metrics)))
                     (testing "count is correct"
                       (is (every? #(= 1 %) (map :count metrics))))
                     (testing "mean is correct"
                       (is (every? #(< 0 %) (map :mean metrics))))
                     (testing "aggregate is correct"
                       ;; since each metric-id has only been hit once, aggregate and mean should be the
                       ;; same
                       (is (every? (fn [{:keys [aggregate mean]}] #(= aggregate mean)) metrics)))
                     (testing "metric-name is correct"
                       (is (= (set (map #(str/join "." %)
                                        master-core/puppet-server-http-client-metrics-for-status))
                              (set (map
                                    ;; re-find returns a vector of results, the second element being the
                                    ;; capture match
                                    #(second
                                      (re-find
                                       #"puppetlabs.localhost.http-client.experimental.with-metric-id.(.*).full-response"
                                       (:metric-name %)))
                                    metrics)))))))))

             (testing "http client metrics are reported to graphite"
               (.report (get-puppetserver-graphite-reporter app))
               (is (every? #(metrics-testutils/reported? @reported-metrics-atom
                                                         :puppetserver
                                                         %)
                           (map #(format "puppetlabs.localhost.http-client.experimental.with-metric-id.%s.full-response.mean" %)
                                ["puppet.report.http"
                                 "puppetdb.command.replace_catalog"
                                 "puppetdb.command.replace_facts"
                                 "puppetdb.command.store_report"
                                 "puppetdb.facts.find"
                                 "puppetdb.facts.search"
                                 "puppetdb.resource.search"
                                 "puppetdb.query"]))))
             (finally
               (jruby-testutils/return-instance jruby-service jruby-instance :http-client-metrics-test)))))))))

(deftest ^:integration add-metric-ids-to-http-client-metrics-list-test
  (let [test-service (tk/service
                      [[:MasterService add-metric-ids-to-http-client-metrics-list!]]
                      (init [this context]
                            (add-metric-ids-to-http-client-metrics-list! [["foo" "bar"]
                                                                          ["hello" "cruel" "world"]])
                            context))]
    (testing "add-metric-ids-to-http-client-metrics-list-fn works"
      (bootstrap-testutils/with-puppetserver-running-with-services
       app
       (conj bootstrap-testutils/services-from-dev-bootstrap
             test-service)
       {:jruby-puppet {:gem-path gem-path
                       :max-active-instances 1
                       :master-code-dir test-resources-code-dir
                       :master-conf-dir master-service-test-runtime-dir}
        :metrics {:server-id "localhost"}}
       (testing "atom has correct metric-ids"
         (let [master-service (tk-app/get-service app :MasterService)
               context (tk-services/service-context master-service)]
           (is (= (set (conj master-core/puppet-server-http-client-metrics-for-status
                             ["foo" "bar"] ["hello" "cruel" "world"]))
                  (set @(:http-client-metric-ids-for-status context))))))
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             jruby-instance (jruby-testutils/borrow-instance jruby-service :add-metric-ids-test)
             container (:scripting-container jruby-instance)]
         (try
           (.runScriptlet
            container
            ;; create a client and assign it to a global variable
            (format "$c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                    8140 true))

           (let [make-request-with-metric-id (fn [metric-id-as-string]
                                               (.runScriptlet
                                                container
                                                ;; So long as the server and port are accessible, it
                                                ;; doesn't actually matter whether the endpoint is
                                                ;; reachable or not - we just need to make requests
                                                ;; with the given metric ids.
                                                (format "$c.get('/fake', {}, {:metric_id => %s})"
                                                        metric-id-as-string)))]
             (testing "http-client-metrics key in master status"
               (make-request-with-metric-id "['foo', 'bar', 'baz']")
               (make-request-with-metric-id "['hello', 'cruel', 'world']")
               (make-request-with-metric-id "['puppetdb', 'query']")
               (make-request-with-metric-id "['fake', 'fake', 'fakery']")
               (let [metric-ids (map :metric-id (get-http-client-metrics-status))]

                 (testing "has metric-ids from master service"
                   (is (some #(= ["puppetdb" "query"] %) metric-ids)))

                 (testing "has metric-ids added by `add-metric-ids-to-http-client-metrics-list`"
                   (is (some #(= ["foo" "bar"] %) metric-ids))
                   (is (some #(= ["hello" "cruel" "world"] %) metric-ids)))

                 (testing "doesn't include metrics for metric-ids it is not supposed to have"
                   (is (not-any? #(= ["fake" "fake" "fakery"] %) metric-ids)))

                 (testing "has correct set of metric-ids"
                   (is (= #{["foo" "bar"] ["hello" "cruel" "world"] ["puppetdb" "query"]}
                          (set metric-ids)))))))
           (finally
             (jruby-testutils/return-instance jruby-service jruby-instance :add-metric-ids-test))))))))

(deftest ^:integration http-report-processor-client-metrics-test
  (testing "HTTP client metrics from the http report processor are added to status"
    (bootstrap-testutils/with-puppetserver-running
     app
     {:jruby-puppet {:gem-path gem-path
                     :max-active-instances 2 ; we need 2 jruby-instances since processing the report uses an instance
                     :master-code-dir test-resources-code-dir
                     :master-conf-dir master-service-test-runtime-dir}
      :metrics {:server-id "localhost"}}
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           jruby-instance (jruby-testutils/borrow-instance jruby-service :http-report-processor-metrics-test)
           container (:scripting-container jruby-instance)]
       (try
         ;; we don't care at all about the content of the report, just that it is valid
         (let [report (.runScriptlet container "Puppet::Transaction::Report.new('apply').to_json")]
           (logutils/with-test-logging
            ;; this test relies on the http report processor being configured in the puppet.conf
            ;; defined by `test-resources-puppet-conf`. We don't care whether the report is actually
            ;; submitted successfully, just that the underlying http client actually makes the
            ;; request, so the configured `reporturl` doesn't have a valid url, and we use
            ;; `with-test-logging` around this request to suppress the error.
            (let [resp (http-put "/puppet/v3/report/localhost?environment=production" report)]
              (testing "report was successfully submitted to http report processor"
                (is (= 200 (:status resp)))
                (is (= "[\"http\"]" (:body resp))))))
           (testing "http-client-metrics key in status has metric for processing report"
             (let [metrics (get-http-client-metrics-status)
                   {:keys [metric-id count mean aggregate metric-name]} (first metrics)]
               (is (= ["puppet" "report" "http"] metric-id))
               (is (= 1 count))
               (is (< 0 mean))
               (is (= (* count mean) aggregate))
               (is (= "puppetlabs.localhost.http-client.experimental.with-metric-id.puppet.report.http.full-response"
                      metric-name)))))
         (finally
           (jruby-testutils/return-instance jruby-service jruby-instance :http-report-processor-metrics-test)))))))

(deftest encoded-spaces-test
  (testing "Encoded spaces should be routed correctly"
    (bootstrap-testutils/with-puppetserver-running
     _
     {:jruby-puppet {:gem-path gem-path
                     :max-active-instances 1
                     :master-conf-dir master-service-test-runtime-dir}}
     ;; SERVER-1954 - In bidi 1.25.0 and later, %20 in a URL would cause a 500 to be raised here instead
     (let [resp (http-get "/puppet/v3/enviro%20nment")]
       (is (= 404 (:status resp)))))))

(deftest ^:integration facts-upload-api
  (bootstrap-testutils/with-puppetserver-running
   app
   {:jruby-puppet {:gem-path gem-path
                   :max-active-instances 2 ; we need 2 jruby-instances since processing the upload uses an instance
                   :master-code-dir test-resources-code-dir
                   :master-conf-dir master-service-test-runtime-dir
                   :master-var-dir (fs/tmpdir)}}
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          jruby-instance (jruby-testutils/borrow-instance jruby-service :facts-upload-endpoint-test)
          container (:scripting-container jruby-instance)]
      (try
        (let [facts (.runScriptlet container "facts = Puppet::Node::Facts.new('puppet.node.test')
                                              facts.values['foo'] = 'bar'
                                              facts.to_json")
              response (http-put "/puppet/v3/facts/puppet.node.test?environment=production" facts)]

          (testing "Puppet Server responds to PUT requests for /puppet/v3/facts"
            (is (= 200 (:status response))))

          (testing "Puppet Server saves facts to the configured facts terminus"
            ;; Ensure the test is configured properly
            (is (= "yaml" (.runScriptlet container "Puppet::Node::Facts.indirection.terminus_class")))
            (let [stored-facts (-> (.runScriptlet container "facts = Puppet::Node::Facts.indirection.find('puppet.node.test')
                                                             (facts.nil? ? {} : facts).to_json")
                                   (json/parse-string))]
              (is (= "bar" (get-in stored-facts ["values" "foo"]))))))
        (finally
          (jruby-testutils/return-instance jruby-service jruby-instance :facts-upload-endpoint-test))))))
