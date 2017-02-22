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
    [puppetlabs.services.jruby.jruby-metrics-core :as jruby-metrics-core]))

(def test-resources-path "./dev-resources/puppetlabs/services/master/master_service_test")
(def test-resources-code-dir (str test-resources-path "/code"))
(def test-resources-conf-dir (str test-resources-path "/conf"))
(def test-resources-puppet-conf (fs/file test-resources-conf-dir "puppet.conf"))

(def master-service-test-runtime-dir "target/master-service-test")

(use-fixtures :once (testutils/with-puppet-conf
                     test-resources-puppet-conf
                     master-service-test-runtime-dir))

(defn http-get
  [url]
  (http-client/get (str "https://localhost:8140" url)
                   {:ssl-cert (str master-service-test-runtime-dir
                                   "/certs/localhost.pem")
                    :ssl-key (str master-service-test-runtime-dir
                                  "/private_keys/localhost.pem")
                    :ssl-ca-cert (str master-service-test-runtime-dir
                                      "/ca/ca.pem")
                    :headers {"Accept" "pson"}
                    :as :text}))

(deftest ^:integration master-service-metrics
  (testing "Metrics computed via use of the master service are correct"
    (bootstrap-testutils/with-puppetserver-running
     app
     {:jruby-puppet {:max-active-instances 1
                     :master-code-dir test-resources-code-dir
                     :master-conf-dir master-service-test-runtime-dir}
      :metrics {:server-id "localhost"}}
     ;; mostly just making sure we can get here w/o exception
     (is (true? true))

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
        :legacy-routes-test
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
                  (is (= "legacy-routes-test" (:reason borrowed-instance)))
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
    (fs/delete-dir master-service-test-runtime-dir)
    (testutils/with-puppet-conf-files
     {"puppet.conf" test-resources-puppet-conf}
     master-service-test-runtime-dir
     (logutils/with-test-logging
      (bootstrap-testutils/with-puppetserver-running
       app
       {:jruby-puppet {:master-conf-dir master-service-test-runtime-dir
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

            (test-path! "capub" "target/master-service-test/ca/ca_pub.pem")
            (test-path! "cakey" "target/master-service-test/ca/ca_key.pem")
            (test-path! "cacert" "target/master-service-test/ca/ca_crt.pem")
            (test-path! "localcacert" "target/master-service-test/ca/ca.pem")
            (test-path! "cacrl" "target/master-service-test/ca/ca_crl.pem")
            (test-path! "hostcrl" "target/master-service-test/ca/crl.pem")
            (test-path! "hostpubkey" "target/master-service-test/public_keys/localhost.pem")
            (test-path! "hostprivkey" "target/master-service-test/private_keys/localhost.pem")
            (test-path! "hostcert" "target/master-service-test/certs/localhost.pem")
            (test-path! "serial" "target/master-service-test/certs/serial")
            (test-path! "cert_inventory" "target/master-service-test/inventory.txt")))))))))

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
