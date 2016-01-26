(ns puppetlabs.services.versioned-code-service.versioned-code-core-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.versioned-code-service.versioned-code-core :as vc-core]
    [puppetlabs.trapperkeeper.testutils.logging :as logging]
    [me.raynes.fs :as fs]))

(def test-resources
  (fs/absolute-path
   "./dev-resources/puppetlabs/services/versioned_code_service/versioned_code_core_test"))

(defn script-path
  [script-name]
  (str test-resources "/" script-name))

(deftest test-code-id-execution
  (testing "code id is successfully generated"
    (is (= "foo" (vc-core/execute-code-id-script! (script-path "echo") "foo"))))

  (testing "stderr is logged if generated"
    (logging/with-test-logging
     (is (= "" (vc-core/execute-code-id-script! (script-path "warn") "foo")))
     (is (logged? (format "Error output generated while calculating code id. Command executed: '%s', stderr: '%s'" (script-path "warn") "foo\n")))))

  (testing "exit-code, stdout and stderr are all logged for non-zero exit"
    (logging/with-test-logging
     (is (nil? (vc-core/execute-code-id-script! (script-path "warn_echo_and_error") "foo")))
     (is (logged? (format "Non-zero exit code returned while calculating code id. Command executed: '%s', exit-code '%d', stdout: '%s', stderr: '%s'" (script-path "warn_echo_and_error") 1 "foo\n" "foo\n")))))

  (testing "nil is returned and error logged for exception during execute-command"
    (logging/with-test-logging
     (is (nil? (vc-core/execute-code-id-script! "false" "foo")))
     (is (logged? "Calculating code id generated an error. Command executed: 'false', error generated: 'An absolute path is required, but 'false' is not an absolute path'")))))