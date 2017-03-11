(ns puppetlabs.services.master.master-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.master.master-service :refer :all]
    [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap-testutils]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [puppetlabs.dujour.version-check :as version-check]
    [me.raynes.fs :as fs]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [cheshire.core :as json]
    [puppetlabs.services.jruby.jruby-puppet-service :as jruby-service]
    [puppetlabs.trapperkeeper.services :as tk-services]
    [puppetlabs.puppetserver.testutils :as testutils]
    [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]
    [schema.core :as schema]
    [puppetlabs.services.master.master-core :as master-core]
    [puppetlabs.services.puppet-profiler.puppet-profiler-core :as puppet-profiler-core]
    [puppetlabs.services.protocols.puppet-profiler :as profiler-protocol]))

(def test-resources-path "./dev-resources/puppetlabs/services/master/master_service_test")
(def test-resources-code-dir (str test-resources-path "/codedir"))
(def test-resources-conf-dir (str test-resources-path "/confdir"))

(def master-service-test-runtime-dir "target/master-service-test")

(use-fixtures :once
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
                      :headers {"Accept" "pson"}
                      :as :text})))

(deftest ^:integration master-service-http-metrics
  (testing "HTTP metrics computed via use of the master service are correct"
    (bootstrap-testutils/with-puppetserver-running
     app
     {:jruby-puppet {:max-active-instances 1
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
       (testing "Catalog compilation increments the catalog counter and adds timing data"
         (let [catalog-metrics (map puppet-profiler-core/catalog-metric (.getCatalogTimers puppet-profiler))
               compile-metric (->> catalog-metrics (filter #(= "compile" (:metric %))))]
           (is (= 1 (count compile-metric)))
           (let [metric (first compile-metric)]
             (is (= 1 (metric :count)))
             (is (= (metric :aggregate) (metric :mean)))))))

     (let [resp (http-get "/status/v1/services?level=debug")]
       (is (= 200 (:status resp)))
       (let [status (json/parse-string (:body resp) true)]
         (is (= #{:jruby-metrics :master :puppet-profiler :status-service}
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
     {:jruby-puppet {:max-active-instances 1
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
         {:jruby-puppet {:master-conf-dir ca-files-test-runtime-dir
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

(deftest ^:integration version-check-test
  (testing "master calls into the dujour version check library using the correct values"
    ; This atom will store the parameters passed to the version-check-test-fn, which allows us to keep the
    ; assertions about their values inside the version-check-test and will also ensure failures will appear if
    ; the master stops calling the check-for-updates! function
    (let [version-check-params  (atom {})
          version-check-test-fn (fn [request-values update-server-url]
                                  (swap! version-check-params #(assoc % :request-values request-values
                                                                        :update-server-url update-server-url)))]
      (with-redefs
       [version-check/check-for-updates! version-check-test-fn]
        (logutils/with-test-logging
         (bootstrap-testutils/with-puppetserver-running-with-mock-jrubies
          "Mocking is safe here because we're not doing anything with JRubies, just making sure
          the service starts and makes the right dujour calls"
          app
          {:jruby-puppet {:max-active-instances 1
                          :master-conf-dir master-service-test-runtime-dir}
           :webserver {:port 8081}
           :product {:update-server-url "http://notarealurl/"
                     :name {:group-id "puppets"
                            :artifact-id "yoda"}}}
          (is (= {:group-id "puppets" :artifact-id "yoda"}
                 (get-in @version-check-params [:request-values :product-name])))
          (is (= "http://notarealurl/" (:update-server-url @version-check-params))))))))

  (testing "master does not make an analytics call to dujour if opt-out exists"
    ; This atom will store the parameters passed to the version-check-test-fn, which allows us to keep the
    ; assertions about their values inside the version-check-test and will also ensure failures will appear if
    ; the master stops calling the check-for-updates! function
    (let [version-check-params  (atom {})
          version-check-test-fn (fn [request-values update-server-url]
                                  (swap! version-check-params #(assoc % :request-values request-values
                                                                        :update-server-url update-server-url)))]
      (with-redefs
       [version-check/check-for-updates! version-check-test-fn]
        (logutils/with-test-logging
         (bootstrap-testutils/with-puppetserver-running-with-mock-jrubies
          "Mocking is safe here because we're not doing anything with JRubies, just making sure
          the service starts and makes the right dujour calls"
          app
          {:jruby-puppet {:max-active-instances 1
                          :master-conf-dir master-service-test-runtime-dir}
           :webserver {:port 8081}
           :product {:update-server-url "http://notarealurl/"
                     :name {:group-id "puppets"
                            :artifact-id "yoda"}
                     :check-for-updates false}}
          (is (= nil (get-in @version-check-params [:request-values :product-name])))
          (is (= nil (:update-server-url @version-check-params)))))))))
