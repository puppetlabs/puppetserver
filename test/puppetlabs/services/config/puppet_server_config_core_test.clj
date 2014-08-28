(ns puppetlabs.services.config.puppet-server-config-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.config.puppet-server-config-core :refer :all]
            [puppetlabs.services.jruby.testutils :as testutils]
            [schema.core :as schema]))

(deftest test-puppet-config-values
  (let [jruby-puppet (testutils/create-jruby-instance)]

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
        webserver-ssl-config {:ssl-cert     "thehostcert"
                              :ssl-key      "thehostprivkey"
                              :ssl-ca-cert  "thecacert"
                              :ssl-crl-path "thecacrl"}]

    (testing (str "no call made to override default webserver settings if "
                  "ssl cert configuration already in webserver settings")
      (init-webserver! override-fn webserver-ssl-config puppet-config)
      (is (nil? @settings-passed)
          "Unexpected settings passed into the override function"))

    (testing (str "expected settings passed to override function when "
                  "none already exist in webserver settings")
      (reset! settings-passed nil)
      (init-webserver! override-fn {} puppet-config)
      (is (= webserver-ssl-config @settings-passed)
          "Unexpected settings passed into the override function"))))
