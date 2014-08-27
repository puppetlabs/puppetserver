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
          (if (contains? puppet-config-keys-which-require-a-non-nil-value k)
            (is (not (nil? (get puppet-config k))))))))))

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
  (let [settings-passed (atom nil)
        override-fn     (fn [settings]
                          (reset! settings-passed settings))]
    (testing (str "expected settings when ssl-server-ca-auth non-nil passed "
                  "through to override function")
      (let [puppet-config   {:hostcert           "thehostcert"
                             :cacert             "thecacert"
                             :cacrl              "thecacrl"
                             :hostprivkey        "thehostprivkey"
                             :ssl-server-ca-auth "thesslservercaauth"}]
        (init-webserver! override-fn puppet-config)
        (is (= {:ssl-cert     "thehostcert"
                :ssl-key      "thehostprivkey"
                :ssl-ca-cert  "thesslservercaauth"
                :ssl-crl-path "thecacrl"}
               @settings-passed)
            "Unexpected settings passed into the override function")))
    (testing (str "expected settings when ssl-server-ca-auth nil passed "
                  "through to override function")
      (reset! settings-passed nil)
      (let [puppet-config   {:hostcert           "thehostcert"
                             :cacert             "thecacert"
                             :cacrl              "thecacrl"
                             :hostprivkey        "thehostprivkey"
                             :ssl-server-ca-auth nil}]
        (init-webserver! override-fn puppet-config)
        (is (= {:ssl-cert     "thehostcert"
                :ssl-key      "thehostprivkey"
                :ssl-ca-cert  "thecacert"
                :ssl-crl-path "thecacrl"}
               @settings-passed)
            "Unexpected settings passed into the override function")))))
