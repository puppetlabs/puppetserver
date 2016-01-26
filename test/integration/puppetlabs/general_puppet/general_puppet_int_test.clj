(ns puppetlabs.general-puppet.general-puppet-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [me.raynes.fs :as fs]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(def test-resources-dir
  (fs/absolute-path "./dev-resources/puppetlabs/general_puppet/general_puppet_int_test"))

(defn script-path
  [script-name]
  (str test-resources-dir "/" script-name))

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(def num-jrubies 1)

(deftest ^:integration test-external-command-execution
  (testing "puppet functions can call external commands successfully"
    ; The generate puppet function runs a fully qualified command with arguments.
    ; This function calls into Puppet::Util::Execution.execute(), which calls into
    ; our shell-utils code via Puppet::Util::ExecutionStub which we call in
    ; Puppet::Server::Execution.
    (testutils/write-site-pp-file
     (format "$a = generate('%s', 'this command echoes a thing'); notify {$a:}"
             (script-path "echo")))
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies}}
     (testing "calling generate successfully executes shell command"
       (let [catalog (testutils/get-catalog)]
         (is (testutils/catalog-contains? catalog "Notify" "this command echoes a thing\n")))))))

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
     (let [catalog (testutils/get-catalog)]
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
     (let [catalog (testutils/post-catalog)]
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
      (let [catalog (testutils/get-catalog)]
        (is (nil? (get catalog "code_id")))
        (is (logged? #"Non-zero exit code returned while running" :error))
        (is (logged? #"Executed an external process which logged to STDERR: production" :warn)))))))

(deftest ^:integration static-file-content-endpoint-test
  (logging/with-test-logging
    (testing "the /static_file_content endpoint successfully streams file content"
      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet
         {:max-active-instances num-jrubies}
         :versioned-code
         {:code-content-command (script-path "echo")}}
        (let [response (http-client/get "https://localhost:8140/puppet/v3/static_file_content/foo/bar/?code-id=foobar&environment=test"
                                        (assoc testutils/ssl-request-options
                                          :as :stream))]
          (is (= 200 (:status response)))
          (is (= "test foobar foo/bar/\n" (slurp (:body response)))))))

    (testing "the /static_file_content endpoint errors if :code-content-command is not set"
      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet
         {:max-active-instances num-jrubies}
         :versioned-code
         {:code-content-command nil}}
        (let [response (http-client/get "https://localhost:8140/puppet/v3/static_file_content/foo/bar/?code-id=foobar&environment=test"
                                        (assoc testutils/ssl-request-options
                                          :as :stream))]
          (is (= 500 (:status response))))))))
