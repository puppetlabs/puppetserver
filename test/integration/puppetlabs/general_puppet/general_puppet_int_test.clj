(ns puppetlabs.general-puppet.general-puppet-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments-int-test :refer [write-site-pp-file get-catalog catalog-contains?]]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/general_puppet/general_puppet_int_test")

(def executables-dir
  (fs/absolute-path "./dev-resources/puppetlabs/puppetserver/shell_utils_test"))

(defn script-path
  [script-name]
  (str executables-dir "/" script-name))

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(def num-jrubies 1)

(deftest ^:integration environment-flush-integration-test
  (testing "environments are flushed after marking expired"
    ; The generate puppet function runs a fully qualified command with arguments.
    ; This function calls into Puppet::Util::Execution.execute(), which calls into
    ; our shell-utils code via Puppet::Util::ExecutionStub which we call in
    ; Puppet::Server::Execution.
    (write-site-pp-file (format "$a = generate('%s', 'this command echoes a thing'); notify {$a:}" (script-path "echo")))
    (bootstrap/with-puppetserver-running app {:jruby-puppet
                                              {:max-active-instances num-jrubies}}
                                         (testing "calling generate successfully executes shell command"
                                           (let [catalog (get-catalog)]
                                             (is (catalog-contains? catalog "Notify" "this command echoes a thing\n")))))))
