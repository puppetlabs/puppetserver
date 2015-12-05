(ns puppetlabs.services.jruby.jruby-pool-test
  (:import (clojure.lang ExceptionInfo))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

(deftest configuration-validation
  (testing "malformed configuration fails"
    (let [malformed-config {:illegal-key [1 2 3]}]
      (is (thrown-with-msg? ExceptionInfo
                            #"Input to create-pool-context does not match schema"
                            (jruby-core/create-pool-context malformed-config nil nil)))))
  (let [minimal-config {:jruby-puppet {:gem-home        "/dev/null"
                                       :master-conf-dir "/dev/null"
                                       :master-var-dir  "/dev/null"
                                       :master-code-dir "/dev/null"
                                       :master-log-dir  "/dev/null"
                                       :master-run-dir  "/dev/null"
                                       :ruby-load-path  ["/dev/null"]}}
        config        (jruby-core/initialize-config minimal-config)]
    (testing "max-active-instances is set to default if not specified"
      (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:max-active-instances config))))
    (testing "max-requests-per-instance is set to 0 if not specified"
      (is (= 0 (:max-requests-per-instance config))))
    (testing "max-requests-per-instance is honored if specified"
      (is (= 5 (-> minimal-config
                   (assoc-in [:jruby-puppet :max-requests-per-instance] 5)
                   (jruby-core/initialize-config)
                   :max-requests-per-instance))))))

(deftest test-jruby-service-core-funcs
  (let [pool-size        2
        config           (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})
        profiler         jruby-testutils/default-profiler
        pool-context     (jruby-core/create-pool-context
                           config profiler jruby-testutils/default-shutdown-fn)
        pool             (jruby-core/get-pool pool-context)]

    (testing "The pool should not yet be full as it is being primed in the
             background."
      (is (= (jruby-core/free-instance-count pool) 0)))

    (jruby-agents/prime-pool! pool-context config profiler)

    (testing "Borrowing all instances from a pool while it is being primed and
             returning them."
      (let [all-the-jrubys (jruby-testutils/drain-pool pool-context pool-size)]
        (is (= 0 (jruby-core/free-instance-count pool)))
        (doseq [instance all-the-jrubys]
          (is (not (nil? instance)) "One of JRubyPuppet instances is nil"))
        (jruby-testutils/fill-drained-pool all-the-jrubys)
        (is (= pool-size (jruby-core/free-instance-count pool)))))

    (testing "Borrowing from an empty pool with a timeout returns nil within the
             proper amount of time."
      (let [timeout              250
            all-the-jrubys       (jruby-testutils/drain-pool pool-context pool-size)
            test-start-in-millis (System/currentTimeMillis)]
        (is (nil? (jruby-core/borrow-from-pool-with-timeout pool-context timeout :test [])))
        (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout)
            "The timeout value was not honored.")
        (jruby-testutils/fill-drained-pool all-the-jrubys)
        (is (= (jruby-core/free-instance-count pool) pool-size)
            "All JRubyPuppet instances were not returned to the pool.")))

    (testing "Removing an instance decrements the pool size by 1."
      (let [jruby-instance (jruby-core/borrow-from-pool pool-context :test [])]
        (is (= (jruby-core/free-instance-count pool) (dec pool-size)))
        (jruby-core/return-to-pool jruby-instance :test [])))

    (testing "Borrowing an instance increments its request count."
      (let [drain-via   (fn [borrow-fn] (doall (repeatedly pool-size borrow-fn)))
            assoc-count (fn [acc jruby]
                          (assoc acc (:id jruby)
                                     (:borrow-count @(:state jruby))))
            get-counts  (fn [jrubies] (reduce assoc-count {} jrubies))]
        (doseq [drain-fn [#(jruby-core/borrow-from-pool pool-context :test [])
                          #(jruby-core/borrow-from-pool-with-timeout pool-context 20000 :test [])]]
          (let [jrubies (drain-via drain-fn)
                counts  (get-counts jrubies)]
            (jruby-testutils/fill-drained-pool jrubies)
            (let [jrubies    (drain-via drain-fn)
                  new-counts (get-counts jrubies)]
              (jruby-testutils/fill-drained-pool jrubies)
              (is (= (ks/keyset counts) (ks/keyset new-counts)))
              (doseq [k (keys counts)]
                (is (= (inc (counts k)) (new-counts k)))))))))))

(deftest prime-pools-failure
  (let [pool-size 2
        config        (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})
        profiler      jruby-testutils/default-profiler
        pool-context  (jruby-core/create-pool-context
                        config profiler jruby-testutils/default-shutdown-fn)
        err-msg       (re-pattern "Unable to borrow JRuby instance from pool")]
    (with-redefs [jruby-internal/create-pool-instance! (fn [_] (throw (IllegalStateException. "BORK!")))]
                 (is (thrown? IllegalStateException (jruby-agents/prime-pool! pool-context config profiler))))
    (testing "borrow and borrow-with-timeout both throw an exception if the pool failed to initialize"
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (jruby-core/borrow-from-pool pool-context :test [])))
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (jruby-core/borrow-from-pool-with-timeout pool-context 120 :test []))))
    (testing "borrow and borrow-with-timeout both continue to throw exceptions on subsequent calls"
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (jruby-core/borrow-from-pool pool-context :test [])))
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (jruby-core/borrow-from-pool-with-timeout pool-context 120 :test []))))))

(deftest test-default-pool-size
  (logutils/with-test-logging
    (let [config (jruby-testutils/jruby-puppet-config)
          profiler jruby-testutils/default-profiler
          pool (jruby-core/create-pool-context config profiler jruby-testutils/default-shutdown-fn)
          pool-state @(:pool-state pool)]
      (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:size pool-state))))))

(defn create-pool-context
  ([max-requests]
    (create-pool-context max-requests 1))
  ([max-requests max-instances]
   (let [config (jruby-testutils/jruby-puppet-config {:max-active-instances max-instances
                                                      :max-requests-per-instance max-requests})
         profiler jruby-testutils/default-profiler
         pool-context (jruby-core/create-pool-context
                        config profiler jruby-testutils/default-shutdown-fn)]
     (jruby-agents/prime-pool! pool-context config profiler)
     pool-context)))

(deftest flush-jruby-after-max-requests
  (testing "JRuby instance is not flushed if it has not exceeded max requests"
    (let [pool-context  (create-pool-context 2)
          instance      (jruby-core/borrow-from-pool pool-context :test [])
          id            (:id instance)]
      (jruby-core/return-to-pool instance :test [])
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
        (is (= id (:id instance))))))
  (testing "JRuby instance is flushed after exceeding max requests"
    (let [pool-context  (create-pool-context 2)]
      (is (= 1 (count (jruby-core/registered-instances pool-context))))
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])
            id (:id instance)]
        (jruby-core/return-to-pool instance :test [])
        (jruby-core/borrow-from-pool pool-context :test [])
        (jruby-core/return-to-pool instance :test [])
        (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
          (is (not= id (:id instance)))
          (jruby-core/return-to-pool instance :test []))
        (testing "instance is removed from registered elements after flushing"
          (is (= 1 (count (jruby-core/registered-instances pool-context))))))
      (testing "Can lock pool after a flush via max requests"
        (let [pool (jruby-internal/get-pool pool-context)]
          (.lock pool)
          (is (nil? @(future (jruby-core/borrow-from-pool-with-timeout
                              pool-context
                              1
                              :test
                              []))))
          (.unlock pool)
          (is (not (nil? @(future (jruby-core/borrow-from-pool-with-timeout
                                  pool-context
                                  1
                                  :test
                                  [])))))))))

  (testing "JRuby instance is not flushed if max requests setting is set to 0"
    (let [pool-context  (create-pool-context 0)
          instance      (jruby-core/borrow-from-pool pool-context :test [])
          id            (:id instance)]
      (jruby-core/return-to-pool instance :test [])
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
        (is (= id (:id instance))))))
  (testing "Can flush a JRuby instance that is not the first one in the pool"
    (let [pool-context  (create-pool-context 2 3)
          instance1     (jruby-core/borrow-from-pool pool-context :test [])
          instance2     (jruby-core/borrow-from-pool pool-context :test [])
          id            (:id instance2)]
      (jruby-core/return-to-pool instance2 :test [])
      ;; borrow it a second time and confirm we get the same instance
      (let [instance2 (jruby-core/borrow-from-pool pool-context :test [])]
        (is (= id (:id instance2)))
        (jruby-core/return-to-pool instance2 :test []))
      ;; borrow it a third time and confirm that we get a different instance
      (let [instance2 (jruby-core/borrow-from-pool pool-context :test [])]
        (is (not= id (:id instance2)))
        (jruby-core/return-to-pool instance2 :test []))
      (jruby-core/return-to-pool instance1 :test []))))
