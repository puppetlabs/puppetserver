(ns puppetlabs.services.jruby.jruby-locking-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-service]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]))

(deftest ^:integration with-lock-test
  (bootstrap/with-puppetserver-running app {:jruby-puppet {:max-active-instances 1}}
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          lock (jruby-testutils/get-lock app)]

      (testing "initial state of write lock is unlocked"
        (is (not (.isWriteLocked lock))))
      (testing "with-lock macro holds write lock while executing body"
        (jruby-service/with-lock jruby-service
          (is (.isWriteLocked lock))))
      (testing "with-lock macro releases write lock after exectuing body"
        (is (not (.isWriteLocked lock)))))))

(deftest ^:integration with-lock-exception-test
  (bootstrap/with-puppetserver-running app {:jruby-puppet {:max-active-instances 1}}
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          lock (jruby-testutils/get-lock app)]

      (testing "initial state of write lock is unlocked"
        (is (not (.isWriteLocked lock))))

      (testing "with-lock macro releases lock even if body throws exception"
        (is (thrown? RuntimeException
                     (jruby-service/with-lock jruby-service
                       (is (.isWriteLocked lock))
                       (throw (RuntimeException. "exception")))))
        (is (not (.isWriteLocked lock)))))))

(deftest ^:integration borrow-and-return-affect-read-lock-test
  (bootstrap/with-puppetserver-running app {:jruby-puppet {:max-active-instances 1}}
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
          lock (jruby-testutils/get-lock app)]

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
