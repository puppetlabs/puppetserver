(ns puppetlabs.services.config.puppet-server-config-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.puppet-server-config :refer :all]
            [puppetlabs.services.config.puppet-server-config-service :refer :all]
            [puppetlabs.services.config.puppet-server-config-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]))

(def service-and-deps
  [puppet-server-config-service jruby-puppet-pooled-service jetty9-service
   profiler/puppet-profiler-service])

(def required-config
  (merge (jruby-testutils/jruby-puppet-tk-config
           (jruby-testutils/jruby-puppet-config 1))
         {:webserver    {:port 8081}}))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/config/puppet_server_config_service_test")

(defn valid-semver-number? [v]
  (re-matches #"[0-9]\.[0-9]\.[0-9]" v))

(deftest config-service-functions
  (tk-testutils/with-app-with-config
    app
    service-and-deps
    (-> required-config
        (assoc :my-config {:foo "bar"})
        (assoc-in [:jruby-puppet :master-conf-dir] (str test-resources-dir "/master/conf")))
    (testing "Basic puppet-server config service function usage"

      (let [service (tk-app/get-service app :PuppetServerConfigService)
            service-config (get-config service)]

        (is (= (-> (:jruby-puppet service-config)
                   (dissoc :master-conf-dir))
               (-> (:jruby-puppet (jruby-testutils/jruby-puppet-tk-config
                                    (jruby-testutils/jruby-puppet-config 1)))
                   (dissoc :master-conf-dir))))
        (is (= (:os-settings service-config)
               (:os-settings (jruby-testutils/jruby-puppet-tk-config
                               (jruby-testutils/jruby-puppet-config 1)))))
        (is (= (:webserver service-config) {:port 8081}))
        (is (= (:my-config service-config) {:foo "bar"}))
        (is (= (set (keys (:puppet-server service-config)))
               (conj core/puppet-config-keys :puppet-version))
            (str "config not as expected: " service-config))

        (testing "The config service has puppet's version available."
          (is (valid-semver-number?
                (get-in-config service [:puppet-server :puppet-version]))))

        (testing "`get-in-config` functions"

          (testing "get a value from TK's config"
            (is (= "bar" (get-in-config service [:my-config :foo]))))

          (testing "get a value from JRuby's config"
            (is (= "localhost" (get-in-config service [:puppet-server :certname])))))

        (testing "Default values passed to `get-in-config` are handled correctly"
          (is (= "default" (get-in-config service [:bogus] "default")))

          (testing "get a value from TK's config (w/ default specified)"
            (is (= "bar"
                   (get-in-config service [:my-config :foo]
                                  "default value, should not be returned"))))

          (testing "get a value from JRuby's config (w/ default specified"
            (is (= "localhost"
                   (get-in-config service [:puppet-server :certname]
                                  "default value, should not be returned")))))))))

(deftest config-key-conflicts
  (testing (str
             "Providing config values that should be read from Puppet results "
             "in an error that mentions all offending config keys.")
    (with-redefs
      [jruby-puppet-core/create-pool-instance
         jruby-testutils/create-mock-pool-instance]
      (with-test-logging
        (is (thrown-with-msg?
              Exception
              #".*configuration.*conflict.*cacrl.*cacert"
              (tk-testutils/with-app-with-config
                app service-and-deps (assoc required-config :cacrl "bogus"
                                                            :cacert "meow")
                ; do nothing - bootstrap should throw the exception
                )))))))
