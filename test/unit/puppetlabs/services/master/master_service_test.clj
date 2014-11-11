(ns puppetlabs.services.master.master-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.master.master-service :refer :all]
    [puppetlabs.services.config.puppet-server-config-service :refer [puppet-server-config-service]]
    [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
    [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
    [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
    [puppetlabs.services.request-handler.request-handler-service :refer [request-handler-service]]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
    [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
    [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
    [puppetlabs.services.version.version-check-service :as version-check-service]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [puppetlabs.services.ca.certificate-authority-service :refer [certificate-authority-service]]
    [me.raynes.fs :as fs]))

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
             jetty9-service
             request-handler-service
             profiler/puppet-profiler-service
             version-check-service/version-check-service
             certificate-authority-service]

            (-> (jruby-testutils/jruby-puppet-tk-config
                  (jruby-testutils/jruby-puppet-config 1))
                (assoc-in [:jruby-puppet :master-conf-dir]
                          "dev-resources/puppetlabs/services/master/master_service_test/conf")
                (assoc :webserver {:port 8081}))

            (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
              (jruby/with-jruby-puppet
                jruby-puppet
                jruby-service

                (letfn [(test-path!
                          [setting expected-path]
                          (is (= (fs/absolute-path expected-path)
                                 (.getSetting jruby-puppet setting)))
                          (is (fs/exists? (fs/absolute-path expected-path))))]

                  (test-path! "capub" "target/master-service-test/ca/ca_pub.pem")
                  (test-path! "cakey" "target/master-service-test/ca/ca_key.pem")
                  (test-path! "cacert" "target/master-service-test/ca/ca_crt.pem")
                  (test-path! "localcacert" "target/master-service-test/ca/ca.pem")
                  (test-path! "cacrl" "target/master-service-test/ca/ca_crl.pem")
                  (test-path! "hostpubkey" "target/master-service-test/public_keys/localhost.pem")
                  (test-path! "hostprivkey" "target/master-service-test/private_keys/localhost.pem")
                  (test-path! "hostcert" "target/master-service-test/certs/localhost.pem")
                  (test-path! "serial" "target/master-service-test/certs/serial")
                  (test-path! "cert_inventory" "target/master-service-test/inventory.txt"))))))
        (finally
          (fs/delete-dir test-dir))))))
