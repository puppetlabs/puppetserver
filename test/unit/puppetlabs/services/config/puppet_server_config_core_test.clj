(ns puppetlabs.services.config.puppet-server-config-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.config.puppet-server-config-core :refer :all]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.testutils :as testutils]
            [schema.core :as schema]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once
              (testutils/with-puppet-conf
                "./dev-resources/puppetlabs/services/config/puppet_server_config_core_test/puppet.conf"))

(deftest ^:integration test-puppet-config-values
  (let [jruby-config (-> (jruby-testutils/jruby-puppet-config)
                         (update :master-conf-dir #(str (fs/normalized %)))
                         (update :master-code-dir #(str (fs/normalized %))))]
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/jruby-service-and-dependencies
     (jruby-testutils/jruby-puppet-tk-config jruby-config)
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
           pool-instance (jruby-testutils/borrow-instance jruby-service :test)
           jruby-puppet (:jruby-puppet pool-instance)
           mock-config (jruby-testutils/mock-puppet-config-settings jruby-config)
           actual-config (get-puppet-config* jruby-puppet)
           setting->keyword (fn [setting] (-> setting
                                              (.replace "_" "-")
                                              keyword))]
       (try
         (testing "usage of get-puppet-config-value"
           (is (= "ldap" (get-puppet-config-value jruby-puppet :ldapserver)))
           (is (= 8140 (get-puppet-config-value jruby-puppet :masterport)))
           (is (= false (get-puppet-config-value jruby-puppet :onetime)))
           (is (= true (get-puppet-config-value jruby-puppet :autoflush)))
           (is (= nil (get-puppet-config-value jruby-puppet :not-a-valid-setting))))
         (testing "get-puppet-config* values match mock config values"
           (let [mock-config-with-keywordized-keys
                 (ks/mapkeys setting->keyword mock-config)]
             (is (= mock-config-with-keywordized-keys actual-config))))
         (testing (str "all needed config values from puppet are available in the map "
                       "returned by `get-puppet-config`")
           (is (map? actual-config))
           (doseq [k puppet-config-keys]
             (is (contains? actual-config k))
             (is (not (nil? (get actual-config k))))))
         (finally
           (jruby-testutils/return-instance jruby-service pool-instance :test)))))))

(deftest test-schema-validation
  (testing "validating a data structure with a missing key"
    (let [config-with-missing-key (dissoc
                                    (zipmap puppet-config-keys (repeat 'anything))
                                    :cacert)]
      (is (not (nil? (schema/check Config config-with-missing-key))))))

  (testing "validating a map with a nil value"
    (let [config-with-nil-value (zipmap puppet-config-keys (repeat nil))]
      (is (not (nil? (schema/check Config config-with-nil-value)))))))

(deftest test-init-webserver!
  (let [settings-passed      (atom nil)
        override-fn          (fn [settings]
                               (reset! settings-passed settings))
        puppet-config        {:hostcert    "thehostcert"
                              :hostprivkey "thehostprivkey"
                              :cacrl       "thecacrl"
                              :localcacert "thelocalcacert"}
        init-webserver-fn    (fn [webserver-settings]
                               (reset! settings-passed nil)
                               (init-webserver! override-fn
                                                webserver-settings
                                                puppet-config)
                               @settings-passed)
        webserver-ssl-config {:ssl-cert     "thehostcert"
                              :ssl-key      "thehostprivkey"
                              :ssl-ca-cert  "thelocalcacert"
                              :ssl-crl-path "thecacrl"}]

    (testing (str "no call made to override default webserver settings if "
                  "full ssl cert configuration already in webserver settings")
      (is (nil? (init-webserver-fn webserver-ssl-config))
          "Override function unexpectedly called with non-nil args"))

    (testing (str "no call made to override default webserver settings if "
                  "at least one overridable setting already in webserver "
                  "settings")
      (doseq [[setting-key setting-value] webserver-ssl-config]
        (let [map-with-one-overridable-setting {setting-key setting-value}]
          (is (nil? (init-webserver-fn map-with-one-overridable-setting))
              (str "Override function unexpectedly called with non-nil args "
                   "for " map-with-one-overridable-setting)))))

    (testing (str "expected settings passed to override function when "
                  "no overridable ones already exist in webserver settings")
      (is (= webserver-ssl-config (init-webserver-fn {}))
          "Unexpected settings passed into the override function"))

    (testing (str "expected settings passed to override function when "
                  "no overridable ones already exist in webserver settings")
      (is (= webserver-ssl-config (init-webserver-fn {:x-non-overridable true}))
          "Unexpected settings passed into the override function"))))
