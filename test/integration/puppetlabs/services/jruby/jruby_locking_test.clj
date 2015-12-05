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
    (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size
                                          :borrow-timeout 1})))

(defn can-borrow-from-different-thread?
  [jruby-service]
  @(future
    (if-let [instance (jruby-protocol/borrow-instance jruby-service :test)]
      (do
        (jruby-protocol/return-instance jruby-service instance :test)
        true))))

(deftest ^:integration with-lock-test
  (tk-bootstrap/with-app-with-config
    app
    [jruby-service/jruby-puppet-pooled-service
     profiler/puppet-profiler-service]
    (jruby-service-test-config 1)
    (jruby-testutils/wait-for-jrubies app)
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
      (testing "initial state of write lock is unlocked"
        (is (can-borrow-from-different-thread? jruby-service))
      (testing "with-lock macro holds write lock while executing body"
        (jruby-service/with-lock jruby-service :with-lock-holds-lock-test
          (is (not (can-borrow-from-different-thread? jruby-service)))))
      (testing "with-lock macro releases write lock after exectuing body"
        (is (can-borrow-from-different-thread? jruby-service)))))))

(deftest ^:integration with-lock-exception-test
  (tk-bootstrap/with-app-with-config
    app
    [jruby-service/jruby-puppet-pooled-service
     profiler/puppet-profiler-service]
    (jruby-service-test-config 1)
    (jruby-testutils/wait-for-jrubies app)
    (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]

      (testing "initial state of write lock is unlocked"
        (is (can-borrow-from-different-thread? jruby-service)))

      (testing "with-lock macro releases lock even if body throws exception"
        (is (thrown? IllegalStateException
                     (jruby-service/with-lock jruby-service :with-lock-exception-test
                      (is (not (can-borrow-from-different-thread?
                                jruby-service)))
                       (throw (IllegalStateException. "exception")))))
        (is (can-borrow-from-different-thread? jruby-service))))))

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

(deftest ^:integration with-lock-and-borrow-contention-test
  (testing "contention for instances with borrows and locking handled properly"
    (tk-bootstrap/with-app-with-config
     app
     [jruby-service/jruby-puppet-pooled-service
      profiler/puppet-profiler-service]
     (jruby-service-test-config 2)
     (jruby-testutils/wait-for-jrubies app)
     (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
       (let [instance (jruby-protocol/borrow-instance
                       jruby-service
                       :with-lock-and-borrow-contention-test)]
         (let [lock-thread-started? (promise)
               unlock-thread? (promise)
               lock-thread (future (jruby-service/with-lock
                                    jruby-service
                                    :with-lock-and-borrow-contention-test
                                    (deliver lock-thread-started? true)
                                    @unlock-thread?))]
           (testing "lock not granted yet when instance still borrowed"
             (Thread/sleep 500)
             (is (not (realized?
                       lock-thread-started?))))
           (jruby-protocol/return-instance
            jruby-service instance :with-lock-and-borrow-contention-test)
           @lock-thread-started?
           (testing "cannot borrow from non-locking thread when locked"
             (is (not (can-borrow-from-different-thread? jruby-service))))
           (deliver unlock-thread? true)
           @lock-thread
           (testing "can borrow from non-locking thread after lock released"
             (is (can-borrow-from-different-thread? jruby-service)))))))))
