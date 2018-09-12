(ns puppetlabs.general-puppet.general-puppet-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.common :as ps-common]))

(def test-resources-dir
  (ks/absolute-path "./dev-resources/puppetlabs/general_puppet/general_puppet_int_test"))

(def environment-dir
  (ks/absolute-path (str testutils/conf-dir "/environments/production")))

(defn script-path
  [script-name]
  (str test-resources-dir "/" script-name))

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(def num-jrubies 1)

(deftest ^:integration test-simple-external-command-execution
  (testing "puppet functions can call external commands successfully that are just one argument"
    ; The generate puppet function runs a fully qualified command with arguments.
    ; This function calls into Puppet::Util::Execution.execute(), which calls into
    ; our shell-utils code via Puppet::Util::ExecutionStub which we call in
    ; Puppet::Server::Execution.
    (testutils/write-site-pp-file
     (format "$a = generate('%s'); notify {$a:}" (script-path "echo_foo")))
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies
           :gem-path gem-path}}
     (testing "calling generate successfully executes shell command"
       (let [catalog (testutils/get-catalog)]
         (is (testutils/catalog-contains? catalog "Notify" "foo\n")))))))

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
          {:max-active-instances num-jrubies
           :gem-path gem-path}}
     (testing "calling generate successfully executes shell command"
       (let [catalog (testutils/get-catalog)]
         (is (testutils/catalog-contains? catalog "Notify" "this command echoes a thing\n")))))))

(deftest ^:integration test-complicated-external-command-execution
  (testing "puppet functions can call more complicated external commands successfully"
    ; The generate puppet function runs a fully qualified command with arguments.
    ; This function calls into Puppet::Util::Execution.execute(), which calls into
    ; our shell-utils code via Puppet::Util::ExecutionStub which we call in
    ; Puppet::Server::Execution.
    (testutils/write-site-pp-file
     (format "$a = generate('%s', '-c', \"echo foo\"); notify {$a: }"
             "/bin/sh"))
    (bootstrap/with-puppetserver-running
      app {:jruby-puppet
           {:max-active-instances num-jrubies
            :gem-path gem-path}}
      (testing "calling generate successfully executes shell command"
        (let [catalog (testutils/get-catalog)]
          (is (testutils/catalog-contains? catalog "Notify" "foo\n")))))))

(deftest ^:integration code-id-request-test-get-catalog
  (testing "when making catalog requests with get"
    (bootstrap/with-puppetserver-running
      app {:jruby-puppet
           {:max-active-instances num-jrubies
            :gem-path gem-path}
           :versioned-code
           {:code-id-command (script-path "echo")
            :code-content-command (script-path "echo")}}

      (testing "and environment is valid code id is injected"
        (let [catalog (testutils/get-catalog)]
          ;; As we have set code-id-command to echo, the code id will
          ;; be the result of running `echo $environment`, which will
          ;; be production here.
          (is (= "production" (get catalog "code_id")))))

      (testing "and environment is invalid 400 is returned"
        (logging/with-test-logging
          (let [response (http-client/get
                          "https://localhost:8140/puppet/v3/catalog/localhost?environment=production;cat"
                          testutils/catalog-request-options)]
            (is (= 400 (:status response)))
            (is (= (ps-common/environment-validation-error-msg "production;cat")
                   (:body response)))))))))

(deftest ^:integration code-id-request-test-post-catalog
  (testing "when making catalog requests with post"
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies
           :gem-path gem-path}
          :versioned-code
          {:code-id-command (script-path "echo")
           :code-content-command (script-path "echo")}}
      (testing "and environment is valid code id is injected"
        (let [catalog (testutils/post-catalog)]
          ;; As we have set code-id-command to echo, the code id will
          ;; be the result of running `echo $environment`, which will
          ;; be production here.
          (is (= "production" (get catalog "code_id")))))
      (testing "and environment is invalid the request fails"
        (logging/with-test-logging
          (let [response (http-client/post
                          "https://localhost:8140/puppet/v3/catalog/localhost"
                          (assoc-in (assoc testutils/catalog-request-options
                                           :body "environment=production;cat")
                                    [:headers "Content-Type"] "application/x-www-form-urlencoded"))]
            (is (= 400 (:status response)))
            (is (= (ps-common/environment-validation-error-msg "production;cat")
                   (:body response)))))))))

(deftest ^:integration code-id-request-test-get-environment
  (bootstrap/with-puppetserver-running
    app {:jruby-puppet
         {:max-active-instances num-jrubies
          :gem-path gem-path}
         :versioned-code
         {:code-id-command (script-path "echo")
          :code-content-command (script-path "echo")}}
    (testutils/write-site-pp-file "site { }")
    (testing "code id is added to the request body for environment requests"
      (let [env-catalog (-> (http-client/get "https://localhost:8140/puppet/v3/environment/production" (assoc testutils/ssl-request-options :as :text))
                            :body
                            json/parse-string)]
        (is (= "production" (get env-catalog "code_id")))))
    (testing "code id is set based on the environment in the URL, not a query param"
      (let [env-catalog (-> (http-client/get "https://localhost:8140/puppet/v3/environment/production?environment=development" (assoc testutils/ssl-request-options :as :text))
                            :body
                            json/parse-string)]
        (is (= "production" (get env-catalog "code_id")))))))

(deftest ^:integration code-id-request-test-non-zero-exit
    (testing "catalog request fails if code-id-command returns a non-zero exit code"
      ; As we have set code-id-command to warn, the code id will
      ; be the result of running `warn_echo_and_error $environment`, which will
      ; exit non-zero and fail the catalog request.
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here, because the catalog request will never make it all
       the way through to the ruby layer due to the code-id-command failure in the
       Clojure layer."
       app {:jruby-puppet
            {:max-active-instances num-jrubies
             :gem-path gem-path}
            :versioned-code
            {:code-id-command (script-path "warn_echo_and_error")
             :code-content-command (script-path "echo")}}
       (logging/with-test-logging
        (let [catalog-response (http-client/get
                                "https://localhost:8140/puppet/v3/catalog/localhost?environment=production"
                                testutils/catalog-request-options)]
          (is (= 500 (:status catalog-response)))
          (is (re-find #"Non-zero exit code returned while running" (:body catalog-response)))
          (is (logged? #"Executed an external process which logged to STDERR: production" :warn)))))))

(deftest ^:integration code-id-request-test-no-environment
  (testing "code id is not added and 400 is returned if environment is not included in request"
    (logging/with-test-logging
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "Mocking is safe here because the Clojure code will raise an error about the
      missing environment information before the request makes it through to the Ruby
      layer."
      app {:jruby-puppet
           {:max-active-instances num-jrubies
            :gem-path gem-path}
           :versioned-code
           {:code-content-command (script-path "echo")
            :code-id-command (script-path "echo")}}
      (let [response (testutils/http-get "puppet/v3/catalog/localhost")]
        (is (= 400 (:status response)))
        (is (logged? #"Error 400 on SERVER")))))))

(deftest ^:integration static-file-content-endpoint-test
  (logging/with-test-logging
   (testing "the /static_file_content endpoint behaves as expected when :code-content-command is set"
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "JRuby mocking is safe here because the static file content endpoint is
      implemented in Clojure."
      app
      {:jruby-puppet
       {:max-active-instances num-jrubies
        :gem-path gem-path}
       :versioned-code
       {:code-content-command (script-path "echo")
        :code-id-command (script-path "echo")}}
      (testing "the /static_file_content endpoint successfully streams file content"
        (let [response (testutils/get-static-file-content "modules/foo/files/bar.txt?code_id=foobar&environment=test")]
          (is (= 200 (:status response)))
          (is (= "application/octet-stream" (get-in response [:headers "content-type"])))
          (is (= "test foobar modules/foo/files/bar.txt\n" (:body response)))))
      (testing "the /static_file_content endpoint successfully streams task content"
        (let [response (testutils/get-static-file-content "modules/foo/tasks/bar.txt?code_id=foobar&environment=test")]
          (is (= 200 (:status response)))
          (is (= "application/octet-stream" (get-in response [:headers "content-type"])))
          (is (= "test foobar modules/foo/tasks/bar.txt\n" (:body response)))))
      (testing "the /static_file_content endpoint successfully streams lib content"
        (let [response (testutils/get-static-file-content "plugins/foo/lib/bar.txt?code_id=foobar&environment=test")]
          (is (= 200 (:status response)))
          (is (= "application/octet-stream" (get-in response [:headers "content-type"])))
          (is (= "test foobar plugins/foo/lib/bar.txt\n" (:body response)))))
      (testing (str "the /static_file_content endpoint successfully streams file content "
                    "from directories other than /modules")
        (let [response (testutils/get-static-file-content "site/foo/files/bar.txt?code_id=foobar&environment=test")]
          (is (= 200 (:status response)))
          (is (= "application/octet-stream" (get-in response [:headers "content-type"])))
          (is (= "test foobar site/foo/files/bar.txt\n" (:body response))))
        (let [response (testutils/get-static-file-content "dist/foo/files/bar.txt?code_id=foobar&environment=test")]
          (is (= 200 (:status response)))
          (is (= "test foobar dist/foo/files/bar.txt\n" (:body response)))))
       (testing "the /static_file_content endpoint validates environment"
         (doseq [[env-encoded env-decoded]
                 [["hi%23cat" "hi#cat"]
                  ["hi%20cat" "hi cat"]
                  ["%20hicat" " hicat"]
                  ["hi%3Bcat" "hi;cat"]
                  ["hicat%20" "hicat "]]]
           (let [response (testutils/get-static-file-content
                           (format "modules/foo/files/bar.txt?code_id=foobar&environment=%s"
                                   env-encoded))]
             (is (= 400 (:status response)))
             (is (= (ps-common/environment-validation-error-msg env-decoded)
                    (:body response))))))

       (testing "the /static_file_content endpoint validates code-id"
         (doseq [[code-id-encoded code-id-decoded]
                 [["hi%23cat" "hi#cat"]
                  ["hi%20cat" "hi cat"]
                  ["%20hicat" " hicat"]
                  ["hicat%20" "hicat "]
                  ["hi%3B%2Fusr%2Fbin%2Fcat" "hi;/usr/bin/cat"]]]
           (let [response (testutils/get-static-file-content
                           (format "modules/foo/files/bar.txt?code_id=%s&environment=test"
                                   code-id-encoded))]
             (is (= 400 (:status response)))
             (is (= (ps-common/code-id-validation-error-msg code-id-decoded)
                    (:body response))))))



       (let [error-message "Error: A /static_file_content request requires an environment, a code-id, and a file-path"]
        (testing "the /static_file_content endpoint returns an error if code_id is not provided"
          (let [response (testutils/get-static-file-content "modules/foo/files/bar.txt?environment=test")]
            (is (= 400 (:status response)))
            (is (= error-message (:body response)))))
        (testing "the /static_file_content endpoint returns an error if environment is not provided"
          (let [response (testutils/get-static-file-content "modules/foo/files/bar.txt?code_id=foobar")]
            (is (= 400 (:status response)))
            (is (= error-message (:body response)))))
        (testing "the /static_file_content endpoint returns an error if file-path is not provided"
          (let [response (testutils/get-static-file-content "?code_id=foobar&environment=test")]
            (is (= 400 (:status response)))
            (is (= error-message (:body response))))))
      (testing "the /static_file_content endpoint returns an error (403) for invalid file-paths"
        (let [response (testutils/get-static-file-content
                        (str "modules/foo/secretstuff/bar?"
                             "environment=test&code_id=foobar"))]
          (is (= 403 (:status response)))
          (is (= (str "Request Denied: A /static_file_content request must be "
                      "a file within the files, lib, or tasks directory of a module.")
                 (:body response)))))
      (testing "the /static_file_content endpoint returns an error (400) for attempted traversals"
        (let [response (testutils/get-static-file-content "modules/foo/files/bar/../../../..?environment=test&code_id=foobar")]
          (is (= 400 (:status response)))
          (is (re-find #"Invalid relative path" (:body response)))))
      (testing "the /static_file_content decodes and rejects alternate encodings of .."
        (let [response (testutils/get-static-file-content
                        "modules/foo/files/bar/%2E%2E/%2E%2E/%2E%2E/%2E%2E?environment=test&code_id=foobar")]
          (is (= 400 (:status response)))
          (is (re-find #"Invalid relative path" (:body response)))))))))

(deftest ^:integration static-file-content-endpoint-test-no-code-content-command
  (logging/with-test-logging
   (testing "the /static_file_content endpoint errors if :code-content-command is not set"
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "JRuby mocking is safe here because the static file content endpoint is
      implemented in Clojure."
      app
      {:jruby-puppet
       {:max-active-instances num-jrubies
        :gem-path gem-path}
       :versioned-code
       {}}
      (let [response (testutils/get-static-file-content "modules/foo/files/bar/?code_id=foobar&environment=test")]
        (is (= 500 (:status response)))
        (is (re-matches #".*Cannot retrieve code content because the \"versioned-code\.code-content-command\" setting is not present in configuration.*"
                        (:body response))))))))

(deftest ^:integration test-config-version-execution
  (testing "config_version is executed correctly"
    (testutils/create-env-conf
     environment-dir
     (format "config_version = \"%s $environment\"" (script-path "echo")))
    (bootstrap/with-puppetserver-running
     app {:jruby-puppet
          {:max-active-instances num-jrubies
           :gem-path gem-path}}
     (let [catalog (testutils/get-catalog)]
       (is (= "production" (get catalog "version")))))))

(deftest ^:integration custom-trusted-oid-mapping-test
  (testing "custom_trusted_oid_mapping works properly"
    (testutils/write-site-pp-file
      "notify { 'trusted_hash':\n\tmessage => \"${trusted['extensions']}\",\n}")
    (fs/delete-dir (fs/file testutils/conf-dir "ssl"))
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet
        {:gem-path gem-path}}
      (let [catalog (testutils/get-catalog)]
        (is (= "{sf => Burning Finger, short => 22}"
               (get-in (first (filter #(= (get % "title") "trusted_hash")
                                      (get catalog "resources")))
                       ["parameters" "message"])))))))
