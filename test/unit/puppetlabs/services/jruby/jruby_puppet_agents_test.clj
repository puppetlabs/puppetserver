(ns puppetlabs.services.jruby.jruby-puppet-agents-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas])
  (:import (puppetlabs.services.jruby.jruby_puppet_schemas RetryPoisonPill)
           (com.puppetlabs.puppetserver JRubyPuppet)
           (java.util.concurrent LinkedBlockingDeque)))

(use-fixtures :once schema-test/validate-schemas)
(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)

(deftest basic-flush-test
  (testing "Flushing the pool results in all new JRuby instances"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config {:max-active-instances 4})))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)
            pool (jruby-core/get-pool pool-context)]
        (jruby-testutils/reduce-over-jrubies! pool 4 #(format "InstanceID = %s" %))
        (is (= #{0 1 2 3}
               (-> (jruby-testutils/reduce-over-jrubies! pool 4 (constantly "InstanceID"))
                   set)))
        (jruby-protocol/flush-jruby-pool! jruby-service)
        ; wait until the flush is complete
        (await (:pool-agent pool-context))
        (let [new-pool (jruby-core/get-pool pool-context)]
          (is (every? true?
                      (jruby-testutils/reduce-over-jrubies!
                        new-pool
                        4
                        (constantly
                          "begin; InstanceID; false; rescue NameError; true; end")))))))))

(deftest retry-poison-pill-test
  (testing "Flush puts a retry poison pill into the old pool"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config {:max-active-instances 1})))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)
            old-pool (jruby-core/get-pool pool-context)
            pool-state-swapped (promise)
            pool-state-watch-fn (fn [key pool-state old-val new-val]
                                  (when (not= (:pool old-val) (:pool new-val))
                                    (remove-watch pool-state key)
                                    (deliver pool-state-swapped true)))]
        ; borrow an instance so we know that the pool is ready
        (jruby/with-jruby-puppet jruby-puppet jruby-service)
        (add-watch (:pool-state pool-context) :pool-state-watch pool-state-watch-fn)
        (jruby-protocol/flush-jruby-pool! jruby-service)
        ; wait until we know the new pool has been swapped in
        @pool-state-swapped
        ; wait until the flush is complete
        (await (:pool-agent pool-context))
        (let [old-pool-instance (jruby-core/borrow-from-pool old-pool)]
          (is (jruby-schemas/retry-poison-pill? old-pool-instance)))))))

(deftest with-jruby-retry-test-via-mock-get-pool
  (testing "with-jruby-puppet retries if it encounters a RetryPoisonPill"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config {:max-active-instances 1})))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            real-pool     (-> (tk-services/service-context jruby-service)
                              :pool-context
                              (jruby-core/get-pool))
            retry-pool    (LinkedBlockingDeque. 1)
            _             (-> retry-pool (RetryPoisonPill.) jruby-core/return-to-pool)
            mock-pools    [retry-pool retry-pool retry-pool real-pool]
            num-borrows   (atom 0)
            get-mock-pool (fn [_] (let [result (nth mock-pools @num-borrows)]
                                    (swap! num-borrows inc)
                                    result))]
        (with-redefs [jruby-core/get-pool get-mock-pool]
          (jruby/with-jruby-puppet
            jruby-puppet
            jruby-service
            (is (instance? JRubyPuppet jruby-puppet))))
        (is (= 4 @num-borrows))))))