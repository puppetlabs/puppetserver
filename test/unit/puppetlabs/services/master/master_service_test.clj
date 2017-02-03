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
    [puppetlabs.kitchensink.core :as ks]))


(deftest ca-files-test
  (testing "CA settings from puppet are honored and the CA
            files are created when the service starts up"
    (let [test-dir (doto "target/master-service-test" fs/mkdir)]
      (try
        (logutils/with-test-logging
         (bootstrap-testutils/with-puppetserver-running
          app
          {:jruby-puppet {:master-conf-dir "dev-resources/puppetlabs/services/master/master_service_test/conf"
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
            (bootstrap-testutils/with-puppetserver-running-with-mock-jrubies
             "Mocking is safe here because we're not doing anything with JRubies, just making sure
             the service starts and makes the right dujour calls"
             app
             {:jruby-puppet {:max-active-instances 1
                             :master-conf-dir "dev-resources/puppetlabs/services/master/master_service_test/conf"}
              :webserver {:port 8081}
              :product {:update-server-url "http://notarealurl/"
                        :name {:group-id "puppets"
                               :artifact-id "yoda"}}}
              (is (= {:group-id "puppets" :artifact-id "yoda"}
                     (get-in @version-check-params [:request-values :product-name])))
              (is (= "http://notarealurl/" (:update-server-url @version-check-params))))))
        (finally
          (fs/delete-dir test-dir)))))

  (testing "master does not make an analytics call to dujour if opt-out exists"
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
            (bootstrap-testutils/with-puppetserver-running-with-mock-jrubies
              "Mocking is safe here because we're not doing anything with JRubies, just making sure
              the service starts and makes the right dujour calls"
              app
              {:jruby-puppet {:max-active-instances 1
                             :master-conf-dir "dev-resources/puppetlabs/services/master/master_service_test/conf"}
              :webserver {:port 8081}
              :product {:update-server-url "http://notarealurl/"
                        :name {:group-id "puppets"
                               :artifact-id "yoda"}
                        :check-for-updates false}}
              (is (= nil (get-in @version-check-params [:request-values :product-name])))
              (is (= nil (:update-server-url @version-check-params))))))
        (finally
          (fs/delete-dir test-dir))))))
