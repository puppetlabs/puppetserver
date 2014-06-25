(ns puppetlabs.master.services.puppet-profiler.puppet-profiler-core-test
  (:import (com.puppetlabs.master PuppetProfiler))
  (:require [clojure.test :refer :all]
            [puppetlabs.master.services.puppet-profiler.puppet-profiler-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]))

(deftest test-logging-profiler
  (with-test-logging
    (testing "logging profiler logs a message"
      (let [profiler  (logging-profiler)
            metric-id (into-array String ["foo" "bar"])
            context   (.start profiler "foo" metric-id)]
        (.finish profiler context "foo" metric-id))
      (is (logged? #"\[foo bar\] \(\d+ ms\) foo" :debug)))))

(deftest test-create-profiler
  (testing "should return nil if there is no profiler config"
    (is (nil? (create-profiler nil)))
    (is (nil? (create-profiler {}))))
  (testing "should return nil if enabled is set to false"
    (is (nil? (create-profiler {:enabled "false"})))
    (is (nil? (create-profiler {:enabled false}))))
  (testing "should return a profiler if enabled is set to true"
    (is (instance? PuppetProfiler (create-profiler {:enabled "true"})))
    (is (instance? PuppetProfiler (create-profiler {:enabled true})))))