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
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol])
  (:import (puppetlabs.services.jruby.jruby_puppet_core RetryPoisonPill)))

(use-fixtures :once schema-test/validate-schemas)

(deftest retry-poison-pill-test
  (testing "Flush puts a retry poison pill into the old pool"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config 1)))
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
        (await (:pool-agent context))
        (let [old-pool-instance (jruby-core/borrow-from-pool old-pool)]
          (is (jruby-core/retry-poison-pill? old-pool-instance)))))))