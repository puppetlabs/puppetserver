(ns puppetlabs.services.versioned-code-service.versioned-code-core-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.versioned-code-service.versioned-code-core :as vc-core]
    [puppetlabs.trapperkeeper.testutils.logging :as logging]
    [puppetlabs.kitchensink.core :as ks])
  (:import (org.apache.commons.io IOUtils)))

(def test-resources
  (ks/absolute-path
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
     (is (logged? (format "Error output generated while running '%s'. stderr: '%s'" (script-path "warn") "foo\n")))))

  (testing "exit-code, stdout and stderr are all logged for non-zero exit"
    (logging/with-test-logging
     (is (thrown? IllegalStateException
                  (vc-core/execute-code-id-script! (script-path "warn_echo_and_error") "foo")))
     (is (logged? (format "Non-zero exit code returned while running '%s'. exit-code: '%d', stdout: '%s', stderr: '%s'" (script-path "warn_echo_and_error") 1 "foo\n" "foo\n")))))

  (testing "nil is returned and error logged for exception during execute-command"
    (logging/with-test-logging
     (is (nil? (vc-core/execute-code-id-script! "false" "foo")))
     (is (logged? "Running script generated an error. Command executed: 'false', error generated: 'An absolute path is required, but 'false' is not an absolute path'")))))

(deftest test-code-content-execution
  (testing "when executing external code content command"
    (let [environment "test"
          code-id "bourgeoisie123"
          file-path "foo/bar/baz"]
      (testing "and code-id is nil we see a schema error"
        (is (thrown-with-msg? Exception #"code-id.*nil"
                              (vc-core/execute-code-content-script! (script-path "echo") environment nil file-path))))
      (testing "and we failed to run the command we see the expected exception"
        (is (thrown-with-msg? IllegalStateException #"generated an error.*false"
                              (vc-core/execute-code-content-script! "false" environment code-id file-path))))
      (testing "and the command returned nonzero"
        (logging/with-test-logging
          (is (thrown-with-msg? IllegalStateException #"Non-zero.*warn_echo_and_error"
                                (vc-core/execute-code-content-script! (script-path "warn_echo_and_error")
                                                                      environment code-id file-path)))))
      (testing "and the command succeeded"
        (testing "with stderr"
          (logging/with-test-logging
            (let [result (vc-core/execute-code-content-script! (script-path "warn") environment code-id file-path)]
              (is (= "" (IOUtils/toString result "UTF-8")))
              (is (logged? (format "Error output generated while running '%s'. stderr: '%s'" (script-path "warn") (format "%s %s %s\n" environment code-id file-path)))))))
        (testing "witout stderr"
          (let [result (vc-core/execute-code-content-script! (script-path "echo") environment code-id file-path)]
            (is (= (format "%s %s %s\n" environment code-id file-path) (IOUtils/toString result "UTF-8")))))))))

(deftest valid-code-id?-test
  (testing "valid-code-id? accepts valid code-ids"
    (is (vc-core/valid-code-id? nil))
    (is (vc-core/valid-code-id? "dcf16ec"))
    (is (vc-core/valid-code-id? "whatMakesJavaBad;isPartlySemiColons;clojureIsRealNice"))
    (is (vc-core/valid-code-id? "master-plan:stage_1:destroy-all-humans"))
    (is (vc-core/valid-code-id? "combining:lots;of_valid-characters"))
    (is (vc-core/valid-code-id? "urn:code-id:1:4dcf1fd;production")))

  (testing "valid-code-id? rejects invalid code-ids"
    (is (not (vc-core/valid-code-id? "bad code id")))
    (is (not (vc-core/valid-code-id? "bad-code-id!")))
    (is (not (vc-core/valid-code-id? "123'456")))
    (is (not (vc-core/valid-code-id? "not-a-good-code-id?")))
    (is (not (vc-core/valid-code-id? "Östersund")))
    (is (not (vc-core/valid-code-id? "( ͡° ͜ʖ ͡°)")))))

(deftest get-current-code-id!-error-test
  (testing "get-current-code-id! throws on invalid code-ids"
    (let [invalid-code-id-script (script-path "invalid_code_id")]
      (is (thrown-with-msg?
            IllegalStateException
            #"Invalid code-id 'not a valid code id'.*"
            (vc-core/get-current-code-id! invalid-code-id-script "testenv"))))))
