(ns puppetlabs.puppetserver.shell-utils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.shell-utils :as sh-utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import (java.io ByteArrayInputStream)
           (com.puppetlabs.puppetserver ShellUtils ShellUtils$ExecutionOptions)))

(def test-resources
  (ks/absolute-path
   "./dev-resources/puppetlabs/puppetserver/shell_utils_test"))

(defn script-path
  [script-name]
  (str test-resources "/" script-name))

(defn parse-env-output
  [env-output]
  (set (str/split-lines env-output)))

(deftest returns-the-exit-code
  (testing "true should return 0"
    (is (zero? (:exit-code (sh-utils/execute-command (script-path "true"))))))
  (testing "false should return 1"
    (is (= 1 (:exit-code (sh-utils/execute-command (script-path "false")))))))

(deftest returns-stdout-correctly
  (testing "echo should add content to stdout"
    (is (= "foo\n" (:stdout (sh-utils/execute-command
                             (script-path "echo")
                             {:args ["foo"]}))))))

(deftest returns-stderr-correctly
  (testing "echo can add content to stderr as well"
    (logging/with-test-logging
     (is (= "bar\n" (:stderr (sh-utils/execute-command
                              (script-path "warn")
                              {:args ["bar"]})))))))

(deftest combines-stderr-and-stdout-correctly
  (logging/with-test-logging
   (let [options (ShellUtils$ExecutionOptions.)
         _ (.setCombineStdoutStderr options true)
         results (ShellUtils/executeCommand (str (script-path "echo_and_warn")
                                                 " baz")
                                            options)]
     (testing "combined info echoed to stdout and stderr captured as output"
       (let [output (.getOutput results)]
         ;; Allow stdout and stderr messages to come in either order since
         ;; the order in which they are read from the different stream
         ;; consuming threads is not reliable.
         (is (or (= "to out: baz\nto err: baz\n" output)
                 (= "to err: baz\nto out: baz\n" output))
             (format "Output produced, '%s', did not match expected output"
                     output))))
     (testing "only info echoed to stderr captured as error"
       (is (= "to err: baz\n" (.getError results))))
     (testing "only stderr info (and not stdout info) is logged"
       (is (logged?
            "Executed an external process which logged to STDERR: to err: baz\n"
            :warn))))))

(deftest pass-args-correctly
  (testing "passes the expected number of args to cmd"
    (is (= 5 (:exit-code (sh-utils/execute-command
                          (script-path "num-args")
                          {:args ["a" "b" "c" "d" "e"]}))))))

(deftest inherits-env-correctly
  (testing "inherits environment variables if not specified"
    (let [env-output (:stdout (sh-utils/execute-command
                               (script-path "list_env_var_names")))
          env (parse-env-output env-output)]
      (is (< 3 (count env))
          (str "Expected at least 3 environment variables, got:\n" env-output))
      (is (contains? env "PATH"))
      (is (contains? env "PWD"))
      (is (contains? env "HOME")))))

(deftest sets-env-correctly
  (testing "sets environment variables correctly"
    (is (= "foo\n" (:stdout (sh-utils/execute-command
                             (script-path "echo_foo_env_var")
                             {:env {"FOO" "foo"}}))))

    (let [env-output (:stdout (sh-utils/execute-command
                               (script-path "list_env_var_names")
                               {:env {"FOO" "foo\nbar"}}))
          env (parse-env-output env-output)
          ;; it seems that the JVM always includes a PWD env var, no
          ;; matter what, and in certain terminals it may also include a few
          ;; other vars, so we are writing the test to be tolerant of that.
          expected-keys #{"FOO" "PWD" "_" "SHLVL"}
          extra-keys (set/difference env expected-keys)]
      (is (empty? extra-keys)
          (str "Found unexpected environment variables:" extra-keys)))))

(deftest pass-stdin-correctly
  (testing "passes stdin stream to command"
    (is (= "foo" (:stdout (sh-utils/execute-command
                             (script-path "cat")
                             {:in (ByteArrayInputStream.
                                   (.getBytes "foo" "UTF-8"))}))))))

(deftest throws-exception-for-non-absolute-path
  (testing "Commands must be given using absolute paths"
    (is (thrown? IllegalArgumentException
                 (sh-utils/execute-command "echo")))))

(deftest throws-exception-for-non-existent-file
  (testing "The given command must exist"
    (is (thrown-with-msg? IllegalArgumentException
                          #"command '/usr/bin/footest' does not exist"
                          (sh-utils/execute-command "/usr/bin/footest")))))

(deftest throws-reasonable-error-for-arguments-in-command
  (testing "A meaningful error is raised if arguments are added to the command"
    (is (thrown-with-msg? IllegalArgumentException
                          #"appears to use command-line arguments, but this is not allowed"
                          (sh-utils/execute-command
                           (str (script-path "echo") " foo"))))))

(deftest can-read-more-than-the-pipe-buffer
  (testing "Doesn't deadlock when reading more than the pipe can hold"
    (is (= 128000 (count (:stdout (sh-utils/execute-command
                                   (script-path "gen-output")
                                   {:args ["128000"]})))))))
