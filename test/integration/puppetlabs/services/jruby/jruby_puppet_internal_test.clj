(ns puppetlabs.services.jruby.jruby-puppet-internal-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas])
  (:import (com.puppetlabs.puppetserver.pool JRubyPool)
           (org.jruby RubyInstanceConfig$CompileMode)
           (clojure.lang ExceptionInfo)))

(deftest get-compile-mode-test
  (testing "returns correct compile modes for SupportedJRubyCompileModes enum"
    (is (= RubyInstanceConfig$CompileMode/JIT
           (jruby-internal/get-compile-mode :jit)))
    (is (= RubyInstanceConfig$CompileMode/FORCE
           (jruby-internal/get-compile-mode :force)))
    (is (= RubyInstanceConfig$CompileMode/OFF
           (jruby-internal/get-compile-mode :off))))
  (testing "returns a valid CompileMode for all values of enum"
    (doseq [mode jruby-schemas/supported-jruby-compile-modes]
      (is (instance? RubyInstanceConfig$CompileMode
                     (jruby-internal/get-compile-mode mode)))))
  (testing "throws an exception if mode is nil"
    (is (thrown? ExceptionInfo
                 (jruby-internal/get-compile-mode nil))))
  (testing "throws an exception for values not in enum"
    (is (thrown? ExceptionInfo
                 (jruby-internal/get-compile-mode :foo)))))

(deftest ^:integration settings-plumbed-into-jruby-container
  (testing "setting plumbed into jruby container for"
    (let [pool (JRubyPool. 1)
          config (jruby-testutils/jruby-puppet-config
                  {:http-client-connect-timeout-milliseconds 2
                   :http-client-idle-timeout-milliseconds 5
                   :http-client-cipher-suites ["TLS_RSA_WITH_AES_256_CBC_SHA256"
                                               "TLS_RSA_WITH_AES_256_CBC_SHA"]
                   :http-client-ssl-protocols ["TLSv1" "TLSv1.2"]
                   :compile-mode :jit})
          instance (jruby-internal/create-pool-instance! pool 0 config #() nil nil)
          container (:scripting-container instance)]
      (= RubyInstanceConfig$CompileMode/JIT
         (.getCompileMode container))
      (try
        (let [settings (into {} (.runScriptlet container
                                               "java.util.HashMap.new
                                                  (Puppet::Server::HttpClient.settings)"))]
          (testing "http_connect_timeout_milliseconds"
            (is (= 2 (settings "http_connect_timeout_milliseconds"))))
          (testing "http_idle_timeout_milliseconds"
            (is (= 5 (settings "http_idle_timeout_milliseconds"))))
          (testing "cipher_suites"
            (is (= ["TLS_RSA_WITH_AES_256_CBC_SHA256"
                    "TLS_RSA_WITH_AES_256_CBC_SHA"]
                   (into [] (settings "cipher_suites")))))
          (testing "ssl_protocols"
            (is (= ["TLSv1" "TLSv1.2"]
                   (into [] (settings "ssl_protocols"))))))
        (finally
          (.terminate (:jruby-puppet instance))
          (.terminate container))))))
