(ns puppetlabs.puppetserver.shell-utils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.shell-utils :as sh-utils]
            [me.raynes.fs :as fs]))

(def test-resources (fs/absolute-path "./dev-resources/puppetlabs/common"))

(defn script-path
  [script-name]
  (str test-resources "/" script-name))

(deftest returns-the-exit-code
  (testing "true should return 0"
    (is (zero? (:exit-code (sh-utils/execute-command (script-path "true"))))))
  (testing "false should return 1"
    (is (= 1 (:exit-code (sh-utils/execute-command (script-path "false")))))))

(deftest returns-stdout-correctly
  (testing "echo should add content to stdout"
    (is (= "foo\n" (:stdout (sh-utils/execute-command (script-path "echo") ["foo"]))))))

(deftest returns-stderr-correctly
  (testing "echo can add content to stderr as well"
    (is (= "bar\n" (:stderr (sh-utils/execute-command (script-path "warn") ["bar"]))))))

(deftest pass-args-correctly
  (testing "passes the expected number of args to cmd"
    (is (= 5 (:exit-code (sh-utils/execute-command (script-path "num-args") ["a" "b" "c" "d" "e"]))))))

(deftest throws-exception-for-non-absolute-path
  (testing "Commands must be given using absolute paths"
    (is (thrown? IllegalArgumentException (sh-utils/execute-command "echo")))))

(deftest throws-exception-for-non-existent-file
  (testing "The given command must exist"
    (is (thrown? IllegalArgumentException (sh-utils/execute-command "/usr/bin/footest")))))

(deftest can-read-more-than-the-pipe-buffer
  (testing "Doesn't deadlock when reading more than the pipe can hold"
    (is (= 128000 (count (:stdout (sh-utils/execute-command (script-path "gen-output") ["128000"])))))))