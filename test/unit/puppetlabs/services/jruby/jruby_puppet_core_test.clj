(ns puppetlabs.services.jruby.jruby-puppet-core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.test :as schema-test]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils])
  (:import (java.io ByteArrayOutputStream PrintStream)))

(use-fixtures :once schema-test/validate-schemas)

(defmacro capture-out
  "capture System.out and return it as the value of :out in the return map.
  The return value of body is available as :return in the return map.

  This macro is intended to be used for JRuby interop.  Please see with-out-str
  for an idiomatic clojure equivalent.

  This macro is not thread safe."
  [& body]
  `(let [return-map# (atom {})
         system-output# (System/out)
         captured-output# (new ByteArrayOutputStream)
         capturing-print-stream# (new PrintStream captured-output#)]
     (try
       (System/setOut capturing-print-stream#)
       (swap! return-map# assoc :return (do ~@body))
       (finally
         (.flush capturing-print-stream#)
         (swap! return-map# assoc :out (.toString captured-output#))
         (System/setOut system-output#)))
     @return-map#))

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

(deftest initialize-puppet-config-test
  (testing "http-client values are used if present"
    (let [http-config {:ssl-protocols ["some-protocol"]
                       :cipher-suites ["some-suite"]
                       :connect-timeout-milliseconds 31415
                       :idle-timeout-milliseconds 42
                       :metrics-enabled false}
          initialized-config (jruby-puppet-core/initialize-puppet-config http-config {} false)]
      (is (= ["some-suite"] (:http-client-cipher-suites initialized-config)))
      (is (= ["some-protocol"] (:http-client-ssl-protocols initialized-config)))
      (is (= 42 (:http-client-idle-timeout-milliseconds initialized-config)))
      (is (= 31415 (:http-client-connect-timeout-milliseconds initialized-config)))
      (is (= false (:http-client-metrics-enabled initialized-config)))))

  (testing "jruby-puppet values are not overridden by defaults"
    (let [jruby-puppet-config {:server-run-dir "one"
                               :server-var-dir "two"
                               :server-conf-dir "three"
                               :server-log-dir "four"
                               :server-code-dir "five"}
          initialized-config (jruby-puppet-core/initialize-puppet-config {} jruby-puppet-config true)]
      (is (= "one" (:server-run-dir initialized-config)))
      (is (= "two" (:server-var-dir initialized-config)))
      (is (= "three" (:server-conf-dir initialized-config)))
      (is (= "four" (:server-log-dir initialized-config)))
      (is (= "five" (:server-code-dir initialized-config)))
      (is (= true (:disable-i18n initialized-config)))))

  (testing "jruby-puppet values are set to defaults if not provided"
    (let [initialized-config (jruby-puppet-core/initialize-puppet-config {} {} false)]
      (is (= "/var/run/puppetlabs/puppetserver" (:server-run-dir initialized-config)))
      (is (= "/opt/puppetlabs/server/data/puppetserver" (:server-var-dir initialized-config)))
      (is (= "/etc/puppetlabs/puppet" (:server-conf-dir initialized-config)))
      (is (= "/var/log/puppetlabs/puppetserver" (:server-log-dir initialized-config)))
      (is (= "/etc/puppetlabs/code" (:server-code-dir initialized-config)))
      (is (= false (:disable-i18n initialized-config)))
      (is (= true (:http-client-metrics-enabled initialized-config)))))

  (testing "jruby-puppet server-* prefer master-* variants to default values"
    (let [initialized-config (jruby-puppet-core/initialize-puppet-config
                              {}
                              {:master-conf-dir "/etc/puppetlabs/puppetserver"
                               :master-code-dir "/etc/puppetlabs/puppetserver/code"
                               :master-log-dir "/log/foo"}
                              false)]
      (is (= "/var/run/puppetlabs/puppetserver" (:server-run-dir initialized-config)))
      (is (= "/opt/puppetlabs/server/data/puppetserver" (:server-var-dir initialized-config)))
      (is (= "/etc/puppetlabs/puppetserver" (:server-conf-dir initialized-config)))
      (is (= "/log/foo" (:server-log-dir initialized-config)))
      (is (= "/etc/puppetlabs/puppetserver/code" (:server-code-dir initialized-config)))))
  (testing "still provides proper values at master-* variants"
    (let [initialized-config (jruby-puppet-core/initialize-puppet-config
                               {}
                               {:master-conf-dir "/my/master/conf"
                                :server-code-dir "/my/server/code"}
                               false)]
      (is (= "/my/master/conf" (:master-conf-dir initialized-config)))
      (is (= "/my/master/conf" (:server-conf-dir initialized-config)))
      (is (= "/my/server/code" (:server-code-dir initialized-config)))
      (is (= "/my/server/code" (:master-code-dir initialized-config)))
      (is (= "/var/run/puppetlabs/puppetserver" (:server-run-dir initialized-config)))
      (is (= "/var/run/puppetlabs/puppetserver" (:master-run-dir initialized-config))))))

(deftest create-jruby-config-test
  (testing "provided values are not overriden"
    (let [jruby-puppet-config (jruby-puppet-core/initialize-puppet-config {} {} false)
          unitialized-jruby-config {:gem-home "/foo"
                                    :gem-path ["/foo" "/bar"]
                                    :compile-mode :jit
                                    :borrow-timeout 1234
                                    :max-active-instances 4321
                                    :max-borrows-per-instance 31415}
          shutdown-fn (fn [] 42)
          initialized-jruby-config (logutils/with-test-logging
                                    (jruby-puppet-core/create-jruby-config
                                     jruby-puppet-config
                                     unitialized-jruby-config
                                     shutdown-fn
                                     nil
                                     nil))]
      (testing "lifecycle functions are not overridden"
        (is (= 42 ((get-in initialized-jruby-config [:lifecycle :shutdown-on-error])))))

      (testing "jruby-config values are not overridden if provided"
        (is (= "/foo" (:gem-home initialized-jruby-config)))
        (is (= "/foo:/bar" (:gem-path initialized-jruby-config)))
        (is (= :jit (:compile-mode initialized-jruby-config)))
        (is (= 1234 (:borrow-timeout initialized-jruby-config)))
        (is (= 4321 (:max-active-instances initialized-jruby-config)))
        (is (= 31415 (:max-borrows-per-instance initialized-jruby-config))))))

  (testing "defaults are used if no values provided"
    (let [jruby-puppet-config (jruby-puppet-core/initialize-puppet-config {} {} false)
          unitialized-jruby-config {:gem-home "/foo"}
          shutdown-fn (fn [] 42)
          initialized-jruby-config (jruby-puppet-core/create-jruby-config
                                    jruby-puppet-config
                                    unitialized-jruby-config
                                    shutdown-fn
                                    nil
                                    nil)]

      (testing "jruby-config default values are used if not provided"
        (is (= :jit (:compile-mode initialized-jruby-config)))
        (is (= jruby-core/default-borrow-timeout (:borrow-timeout initialized-jruby-config)))
        (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:max-active-instances initialized-jruby-config)))
        (is (= 0 (:max-borrows-per-instance initialized-jruby-config))))

      (testing "gem-path defaults to gem-home plus the vendored gems dir if not provided"
        (is (= "/foo:/opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems"
               (:gem-path initialized-jruby-config)))))))
