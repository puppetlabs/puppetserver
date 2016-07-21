(ns puppetlabs.services.master.master-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.master.master-service :refer :all]
    [puppetlabs.services.config.puppet-server-config-service :refer [puppet-server-config-service]]
    [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
    [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as jruby-utils]
    [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
    [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
    [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
    [puppetlabs.services.request-handler.request-handler-service :refer [request-handler-service]]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [puppetlabs.services.ca.certificate-authority-service :refer [certificate-authority-service]]
    [puppetlabs.services.ca.certificate-authority-disabled-service :refer [certificate-authority-disabled-service]]
    [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
    [puppetlabs.services.versioned-code-service.versioned-code-service :refer [versioned-code-service]]
    [puppetlabs.dujour.version-check :as version-check]
    [me.raynes.fs :as fs]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.services.protocols.jruby-puppet :as jruby-puppet]))

(deftest ca-files-test
  (testing "CA settings from puppet are honored and the CA
            files are created when the service starts up"
    (let [test-dir (doto "target/master-service-test" fs/mkdir)]
      (try
        (logutils/with-test-logging
          (tk-testutils/with-app-with-config
            app

            [master-service
             puppet-server-config-service
             jruby/jruby-puppet-pooled-service
             jruby-utils/jruby-pool-manager-service
             jetty9-service
             webrouting-service
             request-handler-service
             profiler/puppet-profiler-service
             certificate-authority-service
             authorization-service
             versioned-code-service
             scheduler-service]

            (-> (jruby-testutils/jruby-puppet-tk-config
                  (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
                (assoc-in [:jruby-puppet :master-conf-dir]
                          "dev-resources/puppetlabs/services/master/master_service_test/conf")
                (assoc :webserver {:port 8081})
                (assoc :web-router-service
                       {:puppetlabs.services.ca.certificate-authority-service/certificate-authority-service ""
                        :puppetlabs.services.master.master-service/master-service "/puppet"}))

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
                 (test-path! "cert_inventory" "target/master-service-test/inventory.txt"))))))
        (finally
          (fs/delete-dir test-dir))))))

(deftest version-check-test
  (testing "master calls into the dujour version check library using the correct values"
    ; This atom will store the parameters passed to the version-check-test-fn, which allows us to keep the
    ; assertions about their values inside the version-check-test and will also ensure failures will appear if
    ; the master stops calling the check-for-updates! function
    (let [version-check-params  (atom {})
          version-check-test-fn (fn [request-values update-server-url]
                                  (swap! version-check-params #(assoc % :request-values request-values
                                                                        :update-server-url update-server-url)))
          test-dir (doto "target/master-service-test" fs/mkdir)]
      (try
        (with-redefs
          [version-check/check-for-updates! version-check-test-fn]
          (logutils/with-test-logging
            (tk-testutils/with-app-with-config
              app

              [master-service
               puppet-server-config-service
               jruby/jruby-puppet-pooled-service
               jruby-utils/jruby-pool-manager-service
               jetty9-service
               webrouting-service
               request-handler-service
               profiler/puppet-profiler-service
               certificate-authority-disabled-service
               authorization-service
               versioned-code-service
               scheduler-service]

              (-> (jruby-testutils/jruby-puppet-tk-config
                    (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
                  (assoc-in [:jruby-puppet :master-conf-dir]
                            "dev-resources/puppetlabs/services/master/master_service_test/conf")
                  (assoc :webserver {:port 8081})
                  (assoc :web-router-service
                         {:puppetlabs.services.ca.certificate-authority-service/certificate-authority-service ""
                          :puppetlabs.services.master.master-service/master-service "/puppet"})
                  (assoc :product {:update-server-url "http://notarealurl/"
                                   :name              {:group-id    "puppets"
                                                       :artifact-id "yoda"}}))
              (is (= {:group-id "puppets" :artifact-id "yoda"}
                     (get-in @version-check-params [:request-values :product-name])))
              (is (= "http://notarealurl/" (:update-server-url @version-check-params))))))
        (finally
          (fs/delete-dir test-dir))))))
