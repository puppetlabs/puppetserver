(ns puppetlabs.services.jruby.jruby-puppet-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(use-fixtures :once schema-test/validate-schemas)

(def min-config
  {:product
   {:name "puppet-server", :update-server-url "http://localhost:11111"},
   :jruby-puppet
   {:gem-home "./target/jruby-gem-home",
    :ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"]},
   :certificate-authority {:certificate-status {:client-whitelist []}}})

(deftest default-num-cpus-test
  (testing "1 jruby instance for a 1 or 2-core box"
    (is (= 1 (jruby-core/default-pool-size 1)))
    (is (= 1 (jruby-core/default-pool-size 2))))
  (testing "2 jruby instances for a 3-core box"
    (is (= 2 (jruby-core/default-pool-size 3))))
  (testing "3 jruby instances for a 4-core box"
    (is (= 3 (jruby-core/default-pool-size 4))))
  (testing "4 jruby instances for anything above 5 cores"
    (is (= 4 (jruby-core/default-pool-size 5)))
    (is (= 4 (jruby-core/default-pool-size 8)))
    (is (= 4 (jruby-core/default-pool-size 16)))
    (is (= 4 (jruby-core/default-pool-size 32)))
    (is (= 4 (jruby-core/default-pool-size 64)))))

(deftest initialize-config-test
  (let [subject (fn [] (jruby-core/initialize-config min-config))]
    (testing "master-{conf,var}-dir settings are optional"
      (is (= "/etc/puppetlabs/puppet" (:master-conf-dir (subject))))
      (is (= "/opt/puppetlabs/server/data/puppetserver" (:master-var-dir (subject)))))
    (testing "(SERVER-647) master-{code,run,log}-dir settings are optional"
      (is (= "/etc/puppetlabs/code" (:master-code-dir (subject))))
      (is (= "/var/run/puppetlabs/puppetserver" (:master-run-dir (subject))))
      (is (= "/var/log/puppetlabs/puppetserver" (:master-log-dir (subject)))))))
