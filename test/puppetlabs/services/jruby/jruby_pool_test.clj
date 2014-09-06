(ns puppetlabs.services.jruby.jruby-pool-test
  (:import (clojure.lang ExceptionInfo))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :refer :all :as core]
            [puppetlabs.services.jruby.testutils :as testutils]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]))

(use-fixtures :each testutils/mock-jruby-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private

(defn drain-pool
  "Drains the JRubyPuppet pool and returns each instance in a vector."
  [pool descriptor size]
  (mapv (fn [_] (borrow-from-pool pool descriptor)) (range size)))

(defn fill-drained-pool
  "Returns a list of JRubyPuppet instances back to their pool."
  [pool descriptor instance-list]
  (doseq [instance instance-list]
    (return-to-pool pool descriptor instance)))

(def production-pool-desc
  "The pool descriptor which should find and return the production pool."
  {:environment :production})

(def test-pool-desc
  "The descriptor which describes the test environment pool"
  {:environment :test})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

(deftest configuration-validation
  (testing "malformed configuration fails"
    (let [malformed-config {:illegal-key [1 2 3]}]
      (is (thrown-with-msg? ExceptionInfo
                            #"Input to validate-config! does not match schema"
                            (validate-config! malformed-config)))))

  (testing "config with no production environment fails"
    (let [no-default-env { :ruby-load-path    jruby-testutils/ruby-load-path
                           :gem-home          jruby-testutils/gem-home
                           :jruby-pools       [{:environment "notdefault"
                                                :size 1}]}]
      (is (thrown? IllegalArgumentException
                   (validate-config! no-default-env)))))

  (testing "illegal environment names fail"
    (let [illegal-env (update-in (testutils/jruby-puppet-config-with-prod-env 1)
                                 [:jruby-pools]
                                 conj {:environment "master", :size 1})]
      (is (thrown? IllegalArgumentException
                   (validate-config! illegal-env)))))

  (testing "a valid basic config works"
    (validate-config! (testutils/jruby-puppet-config-with-prod-env 1)))

  (testing "a valid config with multiple pools works"
    (let [multiple-environments (testutils/jruby-puppet-config-with-prod-test-env 1 42)]
      (validate-config! multiple-environments)
      (is (true? true))))

  (testing "duplicate environment names fail"
    (let [dup-environments (testutils/jruby-puppet-config [{:environment "production"
                                                            :size 1}
                                                           {:environment "production"
                                                            :size 2}])]
      (is (thrown? IllegalArgumentException
                   (validate-config! dup-environments))))))


(deftest jruby-core-standalone-funcs
  (testing "find-pool-by-desc finds pools properly"
    (let [mock-pool {:config (testutils/jruby-puppet-config-with-prod-env 1)
                     :profiler testutils/default-profiler
                     :pools  (atom
                               {:production {:environment   :production
                                             :pool          (instantiate-free-pool 1)
                                             :size          1
                                             :initialized?  true}})}]

      (is (get-pool-data-by-descriptor mock-pool production-pool-desc))

      (is (nil? (get-pool-data-by-descriptor mock-pool
                                             {:environment :doesntexist}))))))

(deftest test-jruby-service-core-funcs
  (let [pool-size        2
        config           (testutils/jruby-puppet-config-with-prod-env pool-size)
        pool             (create-pool-context config testutils/default-profiler)]

    (testing "The pool should not yet be full as it is being primed in the
             background."
      (is (= (free-instance-count pool production-pool-desc) 0)))

    (prime-pools! pool)

    (testing "Borrowing all instances from a pool while it is being primed and
             returning them."
      (let [all-the-jrubys (drain-pool pool production-pool-desc pool-size)]
        (is (= 0 (free-instance-count pool production-pool-desc)))
        (doseq [instance all-the-jrubys]
          (is (not (nil? instance)) "One of JRubyPuppet instances is nil"))
        (fill-drained-pool pool production-pool-desc all-the-jrubys)
        (is (= pool-size (free-instance-count pool production-pool-desc)))))

    (testing "Borrowing from an empty pool with a timeout returns nil within the
             proper amount of time."
      (let [timeout              250
            all-the-jrubys       (drain-pool pool production-pool-desc pool-size)
            test-start-in-millis (System/currentTimeMillis)]
        (is (nil? (borrow-from-pool-with-timeout pool production-pool-desc timeout)))
        (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout)
            "The timeout value was not honored.")
        (fill-drained-pool pool production-pool-desc all-the-jrubys)
        (is (= (free-instance-count pool production-pool-desc) pool-size)
            "All JRubyPuppet instances were not returned to the pool.")))

    (testing "Removing an instance decrements the pool size by 1."
      (let [jruby-instance (borrow-from-pool pool production-pool-desc)]
        (is (= (free-instance-count pool production-pool-desc) (dec pool-size)))
        (return-to-pool pool production-pool-desc jruby-instance)))))

(deftest test-default-pool
  (let [config (testutils/jruby-puppet-config-with-prod-env)]
    (is (= (extract-default-pool-descriptor config)
           {:environment :production}))))

(deftest prime-pools-failure
  (let [pool-size 2
        config    (testutils/jruby-puppet-config-with-prod-env pool-size)
        pool      (create-pool-context config testutils/default-profiler)
        err-msg   (re-pattern "Unable to borrow JRuby instance from pool")]
    (with-redefs [core/create-jruby-instance (fn [x] (throw (IllegalStateException. "BORK!")))]
                 (is (thrown? IllegalStateException (prime-pools! pool))))
    (testing "borrow and borrow-with-timeout both throw an exception if the pool failed to initialize"
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (borrow-from-pool pool production-pool-desc)))
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (borrow-from-pool-with-timeout pool production-pool-desc 120))))
    (testing "borrow and borrow-with-timeout both continue to throw exceptions on subsequent calls"
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (borrow-from-pool pool production-pool-desc)))
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (borrow-from-pool-with-timeout pool production-pool-desc 120))))))

(deftest pool-state-initialization
  (let [pool-size        1
        config           (testutils/jruby-puppet-config-with-prod-env pool-size)
        pool-ctxt        (create-pool-context config testutils/default-profiler)
        prod-pool        (get-pool-data-by-descriptor pool-ctxt production-pool-desc)]
    (is (false? (:initialized? prod-pool)))
    (is (= 1 (:size prod-pool)))
    (prime-pools! pool-ctxt)
    (let [updated-prod-pool (get-pool-data-by-descriptor pool-ctxt production-pool-desc)]
      (is (true? (:initialized? updated-prod-pool))))))

(deftest test-multiple-pools
  (let [prod-size 2
        test-size 2
        config (testutils/jruby-puppet-config-with-prod-test-env prod-size test-size)
        pool (create-pool-context config testutils/default-profiler)]
    (prime-pools! pool)
    (testing "Borrowing all instances from each pool"
      (let [all-prod-instances (drain-pool pool production-pool-desc prod-size)
            all-test-instances (drain-pool pool test-pool-desc       test-size)]
        (is (= (free-instance-count pool production-pool-desc) 0))
        (is (= (free-instance-count pool test-pool-desc) 0))

        (testing "Putting prod instances back should only affect prod pool sizes"
          (fill-drained-pool pool production-pool-desc all-prod-instances)
          (is (= (free-instance-count pool production-pool-desc) prod-size))
          (is (= (free-instance-count pool test-pool-desc) 0)))

        (testing "Putting test instances back should fill the test pool"
          (fill-drained-pool pool test-pool-desc all-test-instances)
          (is (= (free-instance-count pool test-pool-desc) test-size)))))))

(deftest test-default-pool-size
  (let [config testutils/default-config-no-size
        profiler testutils/default-profiler
        pool (create-pool-context config profiler)
        data (core/get-pool-data-by-descriptor
               pool {:environment :production})]
    (= core/default-pool-size (:size data))))

