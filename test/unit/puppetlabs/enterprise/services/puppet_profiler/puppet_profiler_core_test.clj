(ns puppetlabs.enterprise.services.puppet-profiler.puppet-profiler-core-test
  (:import (com.codahale.metrics MetricRegistry Timer)
           (com.puppetlabs.puppetserver PuppetProfiler))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.services.puppet-profiler.puppet-profiler-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]))

(use-fixtures :once schema-test/validate-schemas)

(defn profile
  [profiler message metric-id]
  (let [metric-id (into-array String metric-id)
        context (.start profiler message metric-id)]
    (.finish profiler context message metric-id)))

(deftest test-metrics-profiler
  (testing "metrics profiler keeps timers for all metrics"
    (let [registry (MetricRegistry.)
          profiler (metrics-profiler "localhost" registry)]
      (profile profiler "hi" ["function" "hiera-lookup"])
      (profile profiler "bye" ["compile" "init-environment"])
      (let [metrics-map       (.getMetrics registry)
            expected-metrics  ["puppetlabs.localhost.function"
                               "puppetlabs.localhost.function.hiera-lookup"
                               "puppetlabs.localhost.compile"
                               "puppetlabs.localhost.compile.init-environment"]]
        (is (= (set expected-metrics)
               (.keySet metrics-map)))
        (is (every? #(instance? Timer (.get metrics-map %)) expected-metrics))))))

(deftest test-profiler-via-ruby
  (let [sc      (jruby-internal/empty-scripting-container
                 (jruby-core/initialize-config
                  {:ruby-load-path (jruby-puppet-core/managed-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"])
                   :gem-home "./target/jruby-gems"
                   :compile-mode :off}))
        script  " require 'puppet'
                  require 'puppet/util/profiler'
                  require 'puppet/server'
                  require 'puppet/server/jvm_profiler'

                  require 'java'
                  java_import com.puppetlabs.enterprise.MetricsPuppetProfiler
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
           (into #{} (.getNames registry))))))

(deftest test-initialize
  (testing "does not initialize profiler if profiler is disabled"
    (let [registry  (MetricRegistry.)
          context   (initialize {} "localhost" registry)]
      (is (nil? (:profiler context))))
    (let [registry  (MetricRegistry.)
          context   (initialize {:enabled false} "localhost" registry)]
      (is (nil? (:profiler context)))))
  (testing "logs message and does not initialize profiler if profiler is enabled but metrics are not"
    (with-test-logging
      (let [registry nil
            context (initialize {:enabled true} "localhost" registry)]
        (is (nil? (:profiler context)))
        (is (logged? #"Unable to initialize puppet profiler because metrics are disabled.")))))
  (testing "initializes profiler if enabled"
    (let [registry  (MetricRegistry.)
          context   (initialize {:enabled true} "localhost" registry)]
      (is (instance? PuppetProfiler (:profiler context))))))

(deftest status-tolerates-nil-profiler
  (is (= {:state :running
          :status {}}
         (v1-status nil :debug))))

