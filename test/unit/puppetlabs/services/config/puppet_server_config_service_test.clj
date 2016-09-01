(ns puppetlabs.services.config.puppet-server-config-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.puppet-server-config :refer :all]
            [puppetlabs.services.config.puppet-server-config-service :refer :all]
            [puppetlabs.services.config.puppet-server-config-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :refer [jruby-pool-manager-service]]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [clj-semver.core :as semver]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [puppetlabs.services.protocols.jruby-puppet :as jruby]))

(def service-and-deps
  [puppet-server-config-service jruby-puppet-pooled-service jetty9-service
   profiler/puppet-profiler-service jruby-pool-manager-service scheduler-service])

(def test-resources-dir
  "./dev-resources/puppetlabs/services/config/puppet_server_config_service_test")

(def required-config
  (-> (jruby-testutils/jruby-puppet-tk-config
        (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
      (assoc-in [:webserver :port] 8081)
      (assoc-in [:jruby-puppet :master-conf-dir]
                (str test-resources-dir "/master/conf"))))

(deftest config-service-functions
  (tk-testutils/with-app-with-config
    app
    service-and-deps
    (-> required-config
        (assoc :my-config {:foo "bar"}))
    (testing "Basic puppetserver config service function usage"

      (let [service (tk-app/get-service app :PuppetServerConfigService)
            service-config (get-config service)]

        (is (= (-> (:jruby-puppet service-config)
                   (dissoc :master-conf-dir))
               (-> (:jruby-puppet (jruby-testutils/jruby-puppet-tk-config
                                    (jruby-testutils/jruby-puppet-config {:max-active-instances 1})))
                   (dissoc :master-conf-dir))))
        (is (= (:webserver service-config) {:port 8081}))
        (is (= (:my-config service-config) {:foo "bar"}))
        (is (= (set (keys (:puppetserver service-config)))
               (conj core/puppet-config-keys :puppet-version))
            (str "config not as expected: " service-config))

        (testing "The config service has puppet's version available."
          (is (semver/valid-format?
                (get-in-config service [:puppetserver :puppet-version]))))

        (testing "`get-in-config` functions"

          (testing "get a value from TK's config"
            (is (= "bar" (get-in-config service [:my-config :foo]))))

          (testing "get a value from JRuby's config"
            (is (= "localhost" (get-in-config service [:puppetserver :certname])))))

        (testing "Default values passed to `get-in-config` are handled correctly"
          (is (= "default" (get-in-config service [:bogus] "default")))

          (testing "get a value from TK's config (w/ default specified)"
            (is (= "bar"
                   (get-in-config service [:my-config :foo]
                                  "default value, should not be returned"))))

          (testing "get a value from JRuby's config (w/ default specified"
            (is (= "localhost"
                   (get-in-config service [:puppetserver :certname]
                                  "default value, should not be returned")))))))))

(deftest config-key-conflicts
  (testing (str
             "Providing config values that should be read from Puppet results "
             "in an error that mentions all offending config keys.")
    (with-test-logging
      ;; this is unfortunate.  There is a race condition in this test where
      ;; the JRuby service continues to try to initialize the JRuby pool in
      ;; the background after the test exits, and this can cause transient
      ;; classloader errors or other weird CI failures.  The long-term fix
      ;; is described in SERVER-1087 and related tickets, but for now, we
      ;; just need to make sure that we don't let the test exit until the pool
      ;; is initialized.  In order to do that *and* test the exception, we
      ;; have to expand the `with-app-with-config` macro a bit and dig into
      ;; the guts of TK more than I'd prefer, but the long-term fix is probably
      ;; a ways out.
      (ks-testutils/with-no-jvm-shutdown-hooks
       (let [app (tk/boot-services-with-config
                  service-and-deps
                  (assoc required-config :cacrl "bogus"
                                         :cacert "meow"))]
         (is (thrown-with-msg?
              Exception
              #".*configuration.*conflict.*:cacert, :cacrl"
              (tk-internal/throw-app-error-if-exists! app)))

         ;; Our config for this test only specifies one JRuby instance, so
         ;; once we've successfully done a borrow, we can be assured that the
         ;; initialization has completed and it's safe for us to allow the
         ;; test to exit.
         (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
               jruby-instance (jruby-testutils/borrow-instance jruby-service :config-key-conflicts-test)]
           (jruby-testutils/return-instance jruby-service jruby-instance :config-key-conflicts-test))

         (tk-app/stop app))))))

(deftest multi-webserver-setting-override
  (let [webserver-config {:ssl-cert "thehostcert"
                          :ssl-key "thehostprivkey"
                          :ssl-ca-cert "thelocalcacert"
                          :ssl-crl-path "thecacrl"
                          :port 8081}]
    (testing (str "webserver settings not overridden when mult-webserver config is provided"
                  "and full ssl cert configuration is available")
      (with-test-logging
        (tk-testutils/with-app-with-config
          app
          service-and-deps
          (assoc required-config :webserver {:puppet-server webserver-config})
          (is (logged? #"Not overriding webserver settings with values from core Puppet")))))

    (testing (str "webserver settings not overridden when single webserver is provided"
                  "and full ssl cert configuration is available")
      (with-test-logging
        (tk-testutils/with-app-with-config
          app
          service-and-deps
          (assoc required-config :webserver webserver-config)
          (is (logged? #"Not overriding webserver settings with values from core Puppet")))))))
