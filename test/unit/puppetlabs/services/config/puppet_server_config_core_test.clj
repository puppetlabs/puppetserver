(ns puppetlabs.services.config.puppet-server-config-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.config.puppet-server-config-core :refer :all]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [schema.core :as schema]))

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
                "./dev-resources/puppetlabs/services/config/puppet_server_config_core_test/puppet.conf"))

(deftest test-puppet-config-values
  (let [pool-instance (jruby-testutils/create-pool-instance)
        jruby-puppet  (:jruby-puppet pool-instance)]

    (testing "usage of get-puppet-config-value"
      (is (= "0.0.0.0" (get-puppet-config-value jruby-puppet :bindaddress)))
      (is (= 8140 (get-puppet-config-value jruby-puppet :masterport)))
      (is (= false (get-puppet-config-value jruby-puppet :onetime)))
      (is (= false (get-puppet-config-value jruby-puppet :archive-files)))
      (is (= nil (get-puppet-config-value jruby-puppet :not-a-valid-setting))))

    (testing (str "all needed config values from puppet are available in the map "
                  "returned by `get-puppet-config`")
      (let [puppet-config (get-puppet-config* jruby-puppet)]
        (is (map? puppet-config))
        (doseq [k puppet-config-keys]
          (is (contains? puppet-config k))
          (is (not (nil? (get puppet-config k)))))))))

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
                              :cacert      "thecacert"
                              :cacrl       "thecacrl"
                              :hostprivkey "thehostprivkey"}
        init-webserver-fn    (fn [webserver-settings]
                               (reset! settings-passed nil)
                               (init-webserver! override-fn
                                                webserver-settings
                                                puppet-config)
                               @settings-passed)
        webserver-ssl-config {:ssl-cert     "thehostcert"
                              :ssl-key      "thehostprivkey"
                              :ssl-ca-cert  "thecacert"
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
