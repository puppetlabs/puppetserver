(ns puppetlabs.general-puppet.general-puppet-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments-int-test :refer
             [write-site-pp-file get-catalog catalog-contains? post-catalog]]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  (fs/absolute-path "./dev-resources/puppetlabs/general_puppet/general_puppet_int_test"))

(def common-resources-dir
  (fs/absolute-path "./dev-resources/puppetlabs/common"))

(defn script-path
  [script-name]
  (str common-resources-dir "/" script-name))

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(def num-jrubies 1)

(deftest ^:integration test-external-command-execution
  (testing "puppet functions can call external commands successfully"
    ; The generate puppet function runs a fully qualified command with arguments.
    ; This function calls into Puppet::Util::Execution.execute(), which calls into
    ; our shell-utils code via Puppet::Util::ExecutionStub which we call in
    ; Puppet::Server::Execution.
    (write-site-pp-file
     (format "$a = generate('%s', 'this command echoes a thing'); notify {$a:}"
             (script-path "echo")))
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies}}
     (testing "calling generate successfully executes shell command"
       (let [catalog (get-catalog)]
         (is (catalog-contains? catalog "Notify" "this command echoes a thing\n")))))))

(deftest ^:integration code-id-request-test
  (testing "code id is added to the request body for catalog requests"
    ; As we have set code-id-command to echo, the code id will
    ; be the result of running `echo $environment`, which will
    ; be production here.
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies}
          :versioned-code
          {:code-id-command (script-path "echo")}}
     (let [catalog (get-catalog)]
       (is (= "production" (get catalog "code_id"))))))
  (testing "code id is added to the request body for catalog requests"
    ; As we have set code-id-command to echo, the code id will
    ; be the result of running `echo $environment`, which will
    ; be production here.
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies}
          :versioned-code
          {:code-id-command (script-path "echo")}}
     (let [catalog (post-catalog)]
       (is (= "production" (get catalog "code_id"))))))
  (testing "code id is added to the request body for catalog requests"
    ; As we have set code-id-command to warn, the code id will
    ; be the result of running `warn_echo_and_error $environment`, which will
    ; exit non-zero and return nil.
    (logging/with-test-logging
     (bootstrap/with-puppetserver-running
      app {:jruby-puppet
           {:max-active-instances num-jrubies}
           :versioned-code
           {:code-id-command (script-path "warn_echo_and_error")}}
      (let [catalog (get-catalog)]
        (is (nil? (get catalog "code_id")))
        (is (logged? #"Non-zero exit code returned while calculating code id." :error))
        (is (logged? #"Executed an external process which logged to STDERR: production" :warn)))))))
