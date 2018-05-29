(ns puppetlabs.services.puppet-profiler.puppet-profiler-core-test
  (:import (ch.qos.logback.core Appender)
           (org.slf4j LoggerFactory)
           (ch.qos.logback.classic Logger Level)
           (com.puppetlabs.puppetserver PuppetProfiler)
           (com.codahale.metrics MetricRegistry Timer))
  (:require [clojure.test :refer :all]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

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

(defn profile
  [profiler message metric-id]
  (let [metric-id (into-array String metric-id)
        context (.start profiler message metric-id)]
    (.finish profiler context message metric-id)))

(deftest test-metrics-profiler
  (testing "metrics profiler"
    (logutils/with-test-logging
      (with-test-logs logs
        (let [registry (MetricRegistry.)
              profiler (metrics-profiler "localhost" registry)]
          (profile profiler "hi" ["function" "hiera-lookup"])
          (profile profiler "bye" ["compile" "init-environment"])
          (testing "keeps timers for all metrics"
            (let [metrics-map (.getMetrics registry)
                  expected-metrics ["puppetlabs.localhost.function"
                                    "puppetlabs.localhost.function.hiera-lookup"
                                    "puppetlabs.localhost.compile"
                                    "puppetlabs.localhost.compile.init-environment"]]
              (is (= (set expected-metrics)
                     (.keySet metrics-map)))
              (is (every? #(instance? Timer (.get metrics-map %)) expected-metrics)))))))))

(deftest test-profiler-via-ruby
  (let [sc      (jruby-internal/create-scripting-container
                 (jruby-core/initialize-config
                  {:ruby-load-path (jruby-puppet-core/managed-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"])
                   :gem-home jruby-testutils/gem-home
                   :gem-path jruby-testutils/gem-path
                   :compile-mode :off}))]
    (try
      (let [script " require 'puppet'
                     require 'puppet/util/profiler'
                     require 'puppet/server'
                     require 'puppet/server/jvm_profiler'

                     require 'java'
                     java_import com.puppetlabs.puppetserver.MetricsPuppetProfiler
                     java_import com.codahale.metrics.MetricRegistry

                     registry = MetricRegistry.new
                     profiler = MetricsPuppetProfiler.new('testhost', registry)
                     Puppet::Util::Profiler.add_profiler(Puppet::Server::JvmProfiler.new(profiler))
                     Puppet::Util::Profiler.profile('test', ['foo', 'bar', 'baz']) do
                       sleep(0.01)
                     end
                     registry"
            registry (.runScriptlet sc script)]
        (is (= #{"puppetlabs.testhost.foo"
                 "puppetlabs.testhost.foo.bar"
                 "puppetlabs.testhost.foo.bar.baz"}
               (into #{} (.getNames registry)))))
      (finally
        (.terminate sc)))))

(deftest test-initialize
  (testing "initializes profiler by default"
    (let [registry  (MetricRegistry.)
          context   (initialize {} "localhost" registry)]
      (is (instance? PuppetProfiler (:profiler context)))))
  (testing "initializes profiler if explicitly enabled"
    (let [registry  (MetricRegistry.)
          context   (initialize {:enabled true} "localhost" registry)]
      (is (instance? PuppetProfiler (:profiler context)))))
  (testing "does not initialize profiler if profiler is explicitly disabled"
    (let [registry  (MetricRegistry.)
          context   (initialize {:enabled false} "localhost" registry)]
      (is (nil? (:profiler context))))))

(deftest status-tolerates-nil-profiler
  (is (= {:state :running
          :status {}}
         (v1-status nil :debug))))
