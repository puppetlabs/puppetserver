(ns puppetlabs.services.ca.certificate-authority-disabled-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.services.ca.certificate-authority-disabled-service :as disabled]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]))

(def ssl-dir "./target/certificate-authority-disabled-test/")

(deftest ca-disabled-files-test
  (testing "Ensure no certificates are generated when CA disabled service is enabled."
    (try
      (fs/mkdirs ssl-dir)

      (logutils/with-test-logging
        (tk-testutils/with-app-with-config
          app

          [profiler/puppet-profiler-service
           jruby/jruby-puppet-pooled-service
           disabled/certificate-authority-disabled-service]

          (-> (jruby-testutils/jruby-puppet-tk-config
                (jruby-testutils/jruby-puppet-config 1))
              (assoc-in [:jruby-puppet :master-conf-dir]
                        "dev-resources/puppetlabs/services/ca/certificate_authority_dissabled_test/master/conf"))

          (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
            (jruby/with-jruby-puppet
              jruby-puppet jruby-service
              (doseq [subdir ["certificate_requests"
                              "certs"
                              "private"
                              "private_keys"
                              "public_keys"]]
                (is (empty? (fs/list-dir (str ssl-dir "/" subdir)))))))))
      (finally
        (fs/delete-dir ssl-dir)))))



