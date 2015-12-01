(ns puppetlabs.services.jruby.jruby-locking-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-service]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.core :as tk]))

(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)
(use-fixtures :once schema-test/validate-schemas)

(defn jruby-service-test-config
  [pool-size]
  (jruby-testutils/jruby-puppet-tk-config
    (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})))

(deftest ^:integration with-lock-test
  (tk-bootstrap/with-app-with-config
    app
    [jruby-service/jruby-puppet-pooled-service
     profiler/puppet-profiler-service]
    (jruby-service-test-config 1)
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          lock (jruby-testutils/get-lock-from-app app)]

      (testing "initial state of write lock is unlocked"
        (is (not (.isWriteLocked lock))))
      (testing "with-lock macro holds write lock while executing body"
        (jruby-service/with-lock jruby-service :with-lock-holds-lock-test
          (is (.isWriteLocked lock))))
      (testing "with-lock macro releases write lock after exectuing body"
        (is (not (.isWriteLocked lock)))))))

(deftest ^:integration with-lock-exception-test
  (tk-bootstrap/with-app-with-config
    app
    [jruby-service/jruby-puppet-pooled-service
     profiler/puppet-profiler-service]
    (jruby-service-test-config 1)
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          lock (jruby-testutils/get-lock-from-app app)]

      (testing "initial state of write lock is unlocked"
        (is (not (.isWriteLocked lock))))

      (testing "with-lock macro releases lock even if body throws exception"
        (is (thrown? IllegalStateException
                     (jruby-service/with-lock jruby-service :with-lock-exception-test
                       (is (.isWriteLocked lock))
                       (throw (IllegalStateException. "exception")))))
        (is (not (.isWriteLocked lock)))))))

(deftest ^:integration with-lock-event-notification-test
  (testing "locking sends event notifications"
    (let [events (atom [])
          callback (fn [{:keys [type] :as event}]
                     (swap! events conj type))
          event-service (tk/service [[:JRubyPuppetService register-event-handler]]
                                    (init [this context]
                                          (register-event-handler callback)
                                          context))]
      (tk-bootstrap/with-app-with-config
        app
        [jruby-service/jruby-puppet-pooled-service
         profiler/puppet-profiler-service
         event-service]
        (jruby-service-test-config 1)
        (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]

          (testing "locking events trigger event notifications"
            (jruby-service/with-jruby-puppet
              jruby-puppet
              jruby-service
              :with-lock-events-test
              (testing "borrowing a jruby triggers 'requested'/'borrow' events"
                (is (= [:instance-requested :instance-borrowed] @events))))
            (testing "returning a jruby triggers 'returned' event"
              (is (= [:instance-requested :instance-borrowed :instance-returned] @events)))
            (jruby-service/with-lock
              jruby-service
              :with-lock-events-test
              (testing "acquiring a lock triggers 'lock-requested'/'lock-acquired' events"
                (is (= [:instance-requested :instance-borrowed :instance-returned
                        :lock-requested :lock-acquired] @events)))))
          (testing "releasing the lock triggers 'lock-released' event"
            (is (= [:instance-requested :instance-borrowed :instance-returned
                    :lock-requested :lock-acquired :lock-released] @events))))))))

(deftest ^:integration borrow-and-return-affect-read-lock-test
  (tk-bootstrap/with-app-with-config
    app
    [jruby-service/jruby-puppet-pooled-service
     profiler/puppet-profiler-service]
    (jruby-service-test-config 1)
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          lock (jruby-testutils/get-lock-from-app app)]

      (is (= 0 (.getReadLockCount lock)))
      (is (not (.isWriteLocked lock)))

      (let [instance (jruby-protocol/borrow-instance jruby-service :test-borrow-and-read-lock)]
        (testing "borrow-instance acquires a read lock and does not affect write lock"
          (is (= 1 (.getReadLockCount lock)))
          (is (not (.isWriteLocked lock))))

        (testing "return-instance releases a read lock and does not affect write lock"
          (jruby-protocol/return-instance jruby-service instance :test-return-and-read-lock)
          (is (= 0 (.getReadLockCount lock)))
          (is (not (.isWriteLocked lock))))))))
