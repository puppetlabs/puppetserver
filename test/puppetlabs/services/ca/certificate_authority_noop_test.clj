(ns puppetlabs.services.ca.certificate-authority-noop-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.services.ca.noop-service :as noop]
            [puppetlabs.services.config.puppet-server-config-service :as config]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as webserver]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]))

(def ssl-dir "target/master-service-test")

(deftest ca-files-test
  (testing "CA settings from puppet are honored and the CA
            files are created when the service starts up"
    (let [test-dir (doto "target/master-service-test" fs/mkdir)]
      (try
        (logutils/with-test-logging
          (tk-testutils/with-app-with-config
            app

            [webserver/jetty9-service
             profiler/puppet-profiler-service
             noop/certificate-authority-noop-service
             config/puppet-server-config-service
             jruby/jruby-puppet-pooled-service]

            (-> (jruby-testutils/jruby-puppet-tk-config
                  (jruby-testutils/jruby-puppet-config 1))
                (assoc-in [:jruby-puppet :master-conf-dir]
                          "dev-resources/puppetlabs/services/master/conf")
                (assoc :webserver {:port 8081}))

            (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
              (jruby/with-jruby-puppet
                jruby-puppet
                jruby-service

                (doseq [subdir ["/ca" "/certs" "/public_keys" "/private_keys"]]
                  (is (not (fs/exists? (str ssl-dir subdir)))))))))
        (finally
          (fs/delete-dir test-dir))))))



