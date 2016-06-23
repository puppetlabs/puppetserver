(ns puppetlabs.services.ca.certificate-authority-disabled-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.services.ca.certificate-authority-disabled-service :as disabled]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-puppet]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as jruby-utils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :as tk-auth]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-puppet-protocol]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]))



(def puppet-conf-dir "./target/ca-disabled-service-test")
(def ssl-dir (fs/file puppet-conf-dir "ssl"))

(use-fixtures
  :once
  (testutils/with-puppet-conf
    "./dev-resources/puppetlabs/services/ca/certificate_authority_disabled_test/puppet.conf"
    puppet-conf-dir))

(deftest ca-disabled-files-test
  (testing "Ensure no certificates are generated when CA disabled service is enabled."
    (logutils/with-test-logging
      (tk-testutils/with-app-with-config
        app

        [profiler/puppet-profiler-service
         jruby-puppet/jruby-puppet-pooled-service
         jruby-utils/jruby-pool-manager-service
         disabled/certificate-authority-disabled-service
         tk-auth/authorization-service]

        (-> (jruby-testutils/jruby-puppet-tk-config
              (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
            (assoc-in [:jruby-puppet :master-conf-dir]
                      puppet-conf-dir))

        (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
          (jruby/with-jruby-puppet
           jruby-puppet jruby-service :ca-disabled-files-test
           (is (not (nil? (fs/list-dir ssl-dir))))
           (is (empty? (fs/list-dir (str ssl-dir "/ca"))))))))))
