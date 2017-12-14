(ns puppetlabs.services.config.puppet-server-config-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.puppet-server-config :refer :all]
            [puppetlabs.services.config.puppet-server-config-service :refer :all]
            [puppetlabs.services.config.puppet-server-config-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [clj-semver.core :as semver]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.kitchensink.testutils :as ks-testutils]))

(def service-and-deps
  (conj jruby-testutils/jruby-service-and-dependencies
        puppet-server-config-service))

(defn service-and-deps-with-mock-jruby
  [config]
  (jruby-testutils/add-mock-jruby-pool-manager-service
   service-and-deps
   config))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/config/puppet_server_config_service_test")

(def required-config
  (-> (jruby-testutils/jruby-puppet-tk-config
        (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
      (assoc :webserver {:port 8081})
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
                   (dissoc :master-conf-dir)
                   (dissoc :profiler-output-file))
               (-> (:jruby-puppet (jruby-testutils/jruby-puppet-tk-config
                                    (jruby-testutils/jruby-puppet-config {:max-active-instances 1})))
                   (dissoc :master-conf-dir)
                   (dissoc :profiler-output-file))))
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
      (ks-testutils/with-no-jvm-shutdown-hooks
       (let [config (assoc required-config :cacrl "bogus" :cacert "meow")
             app (tk/boot-services-with-config
                  (service-and-deps-with-mock-jruby config)
                  config)]
         (is (thrown-with-msg?
              Exception
              #".*configuration.*conflict.*:cacert, :cacrl"
              (tk-internal/throw-app-error-if-exists! app)))
         (tk-app/stop app))))))

(deftest multi-webserver-setting-override
  (let [webserver-config {:ssl-cert "thehostcert"
                          :ssl-key "thehostprivkey"
                          :ssl-ca-cert "thelocalcacert"
                          :ssl-crl-path "thecacrl"
                          :port 8081
                          :default-server true}]
    (testing (str "webserver settings not overridden when mult-webserver config is provided"
                  "and full ssl cert configuration is available")
      (with-test-logging
       (let [config (assoc required-config :webserver
                                           {:puppet-server webserver-config})]
         (tk-testutils/with-app-with-config
          app
          (service-and-deps-with-mock-jruby config)
          config
          (is (logged? #"Not overriding webserver settings with values from core Puppet"))))))

    (testing (str "webserver settings not overridden when single webserver is provided"
                  "and full ssl cert configuration is available")
      (let [config (assoc required-config :webserver webserver-config)]
        (with-test-logging
         (tk-testutils/with-app-with-config
          app
          (service-and-deps-with-mock-jruby config)
          config
          (is (logged? #"Not overriding webserver settings with values from core Puppet"))))))))
