(ns puppetlabs.services.puppet-profiler.puppet-profiler-core-test
  (:import (ch.qos.logback.core Appender)
           (org.slf4j LoggerFactory)
           (ch.qos.logback.classic Logger Level)
           (com.puppetlabs.puppetserver LoggingPuppetProfiler PuppetProfiler))
  (:require [clojure.test :refer :all]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :refer :all]))

;; Our normal testutils for logging only work with clojure.tools.logging.
(defmacro with-test-logs
  [log-output-var & body]
  `(let [~log-output-var  (atom [])
         appender#         (proxy [Appender] []
                             (getName [] "test")
                             (setName [name#])
                             (doAppend [e#] (swap! ~log-output-var conj e#)))
         root#             (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME)
         orig-level#       (.getLevel root#)]
     (.setLevel root# Level/DEBUG)
     (.addAppender root# appender#)
     ~@body
     (.detachAppender root# appender#)
     (.setLevel root# orig-level#)))

(defn test-logs-contain?
  [logs pattern level]
  (some (fn [event] (and (re-find pattern (.getMessage event))
                         (= level (.getLevel event))))
        @logs))

(deftest test-logging-profiler
  (with-test-logs logs
    (testing "logging profiler logs a message"
      (let [profiler (LoggingPuppetProfiler.)
            metric-id (into-array String ["foo" "bar"])
            context (.start profiler "foo" metric-id)]
        (.finish profiler context "foo" metric-id))
      (is (test-logs-contain? logs #"\[foo bar\] \(\d+ ms\) foo" Level/DEBUG)))))

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