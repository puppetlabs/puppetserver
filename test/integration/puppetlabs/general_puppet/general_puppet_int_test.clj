(ns puppetlabs.general-puppet.general-puppet-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [me.raynes.fs :as fs]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.kitchensink.core :as ks]))

(def test-resources-dir
  (ks/absolute-path "./dev-resources/puppetlabs/general_puppet/general_puppet_int_test"))

(defn script-path
  [script-name]
  (str test-resources-dir "/" script-name))

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(def num-jrubies 1)

(defn get-static-file-content
  [url-end]
  (http-client/get (str "https://localhost:8140/puppet/v3/static_file_content/" url-end)
                   (assoc testutils/ssl-request-options
                     :as :text)))

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
          {:code-id-command (script-path "echo")
           :code-content-command (script-path "echo")}}
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
          {:code-id-command (script-path "echo")
           :code-content-command (script-path "echo")}}
     (let [catalog (testutils/post-catalog)]
       (is (= "production" (get catalog "code_id"))))))
  (testing "catalog request fails if code-id-command returns a non-zero exit code"
    ; As we have set code-id-command to warn, the code id will
    ; be the result of running `warn_echo_and_error $environment`, which will
    ; exit non-zero and fail the catalog request.
    (logging/with-test-logging
     (bootstrap/with-puppetserver-running
      app {:jruby-puppet
           {:max-active-instances num-jrubies}
           :versioned-code
           {:code-id-command (script-path "warn_echo_and_error")
            :code-content-command (script-path "echo")}}
      (let [catalog-response (http-client/get
                               "https://localhost:8140/puppet/v3/catalog/localhost?environment=production"
                               testutils/catalog-request-options)]
        (is (= 500 (:status catalog-response)))
        (is (re-matches #".*code-id could not be retrieved" (:body catalog-response)))
        (is (logged? #"Non-zero exit code returned while running" :error))
        (is (logged? #"Executed an external process which logged to STDERR: production" :warn))))))
  (testing "code id is not added and 400 is returned if environment is not included in request"
    (logging/with-test-logging
     (bootstrap/with-puppetserver-running
      app {:jruby-puppet
           {:max-active-instances num-jrubies}
           :versioned-code
           {:code-content-command (script-path "echo")
            :code-id-command (script-path "echo")}}
      (let [response (testutils/http-get "puppet/v3/catalog/localhost")]
        (is (= 400 (:status response)))
        (is (logged? #"Error 400 on SERVER")))))))

(deftest ^:integration static-file-content-endpoint-test
  (logging/with-test-logging
   (testing "the /static_file_content endpoint behaves as expected when :code-content-command is set"
     (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet
       {:max-active-instances num-jrubies}
       :versioned-code
       {:code-content-command (script-path "echo")
        :code-id-command (script-path "echo")}}
      (testing "the /static_file_content endpoint successfully streams file content"
        (let [response (get-static-file-content "modules/foo/files/bar.txt?code_id=foobar&environment=test")]
          (is (= 200 (:status response)))
          (is (= "test foobar modules/foo/files/bar.txt\n" (:body response)))))
      (let [error-message "Error: A /static_file_content request requires an environment, a code-id, and a file-path"]
        (testing "the /static_file_content endpoint returns an error if code_id is not provided"
          (let [response (get-static-file-content "modules/foo/files/bar.txt?environment=test")]
            (is (= 400 (:status response)))
            (is (= error-message (:body response)))))
        (testing "the /static_file_content endpoint returns an error if environment is not provided"
          (let [response (get-static-file-content "modules/foo/files/bar.txt?code_id=foobar")]
            (is (= 400 (:status response)))
            (is (= error-message (:body response)))))
        (testing "the /static_file_content endpoint returns an error if file-path is not provided"
          (let [response (get-static-file-content "?code_id=foobar&environment=test")]
            (is (= 400 (:status response)))
            (is (= error-message (:body response))))))
      (testing "the /static_file_content endpoint returns an error (403) for invalid file-paths or attempted traversals"
        (let [response (get-static-file-content "modules/foo/files/bar/../../../..?environment=test&code_id=foobar")]
          (is (= 403 (:status response)))
          (is (= (str "Request Denied: A /static_file_content request must be "
                      "a file within the files directory of a module.") (:body response)))))
      (testing "the /static_file_content decodes and rejects alternate encodings of .."
        (let [response (get-static-file-content
                        "modules/foo/files/bar/%2E%2E/%2E%2E/%2E%2E/%2E%2E?environment=test&code_id=foobar")]
          (is (= 403 (:status response)))
          (is (= (str "Request Denied: A /static_file_content request must be "
                      "a file within the files directory of a module.") (:body response)))))))))

(deftest ^:integration static-file-content-endpoint-test-no-code-content-command
  (logging/with-test-logging
   (testing "the /static_file_content endpoint errors if :code-content-command is not set"
     (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet
       {:max-active-instances num-jrubies}
       :versioned-code
       {}}
      (let [response (get-static-file-content "modules/foo/files/bar/?code_id=foobar&environment=test")]
        (is (= 500 (:status response)))
        (is (re-matches #".*Cannot retrieve code content because the \"versioned-code\.code-content-command\" setting is not present in configuration.*"
                        (:body response))))))))
