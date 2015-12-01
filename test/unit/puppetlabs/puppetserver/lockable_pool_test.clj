(ns puppetlabs.puppetserver.lockable-pool-test
  (:require [clojure.test :refer :all])
  (:import (com.puppetlabs.puppetserver.pool JRubyPool)))

(defn create-empty-pool
  [size]
  (JRubyPool. size))

(defn create-populated-pool
  [size]
  (let [pool (create-empty-pool size)]
    (dotimes [i size]
      (.register pool (str "foo" i)))
    pool))

(defn borrow-n-instances
  [pool n]
  (doall (for [_ (range n)]
           (.borrowItem pool))))

(defn return-instances
  [pool instances]
  (doseq [instance instances]
    (.releaseItem pool instance)))

(deftest pool-lock-is-blocking-test
  (let [pool (create-populated-pool 3)
        instances (borrow-n-instances pool 3)
        future-started? (promise)
        lock-acquired? (promise)
        unlock? (promise)]
    (is (= 3 (count instances)))
    (let [lock-thread (future (deliver future-started? true)
                              (.lock pool)
                              (deliver lock-acquired? true)
                              @unlock?
                              (.unlock pool))]
      @future-started?
      (testing "pool.lock() blocks until all instances are returned to the pool"
        (is (not (realized? lock-acquired?)))

        (testing "other threads may successfully return instances while pool.lock() is being executed"
          (.releaseItem pool (first instances))
          (is (not (realized? lock-acquired?)))
          (.releaseItem pool (second instances))
          (is (not (realized? lock-acquired?)))
          (.releaseItem pool (nth instances 2)))

        @lock-acquired?
        (is (not (realized? lock-thread)))
        (deliver unlock? true)
        @lock-thread
        ;; make sure we got here
        (is (true? true)))

      (testing "borrows may be resumed after unlock()"
        (let [instance (.borrowItem pool)]
          (.releaseItem pool instance))
        ;; make sure we got here
        (is (true? true))))))

(deftest pool-lock-blocks-borrows-test
  (testing "no other threads may borrow once pool.lock() has been invoked (before or after it returns)"
    (let [pool (create-populated-pool 2)
          instances (borrow-n-instances pool 2)
          lock-thread-started? (promise)
          lock-acquired? (promise)
          unlock? (promise)]
      (is (= 2 (count instances)))
      (let [lock-thread (future (deliver lock-thread-started? true)
                                (.lock pool)
                                (deliver lock-acquired? true)
                                @unlock?
                                (.unlock pool))]
        @lock-thread-started?
        (is (not (realized? lock-acquired?)))
        (let [borrow-after-lock-requested-thread-started? (promise)
              borrow-after-lock-requested-instance-acquired? (promise)
              borrow-after-lock-requested-thread
              (future (deliver borrow-after-lock-requested-thread-started? true)
                      (let [instance (.borrowItem pool)]
                        (deliver borrow-after-lock-requested-instance-acquired? true)
                        (.releaseItem pool instance)))]
          @borrow-after-lock-requested-thread-started?
          (is (not (realized? borrow-after-lock-requested-instance-acquired?)))

          (return-instances pool instances)
          @lock-acquired?
          (is (not (realized? borrow-after-lock-requested-instance-acquired?)))
          (is (not (realized? lock-thread)))

          (let [borrow-after-lock-acquired-thread-started? (promise)
                borrow-after-lock-acquired-instance-acquired? (promise)
                borrow-after-lock-acquired-thread
                (future (deliver borrow-after-lock-acquired-thread-started? (promise))
                        (let [instance (.borrowItem pool)]
                          (deliver borrow-after-lock-acquired-instance-acquired? true)
                          (.releaseItem pool instance)))]
            @borrow-after-lock-acquired-thread-started?
            (is (not (realized? borrow-after-lock-acquired-instance-acquired?)))

            (deliver unlock? true)
            @lock-thread
            @borrow-after-lock-requested-instance-acquired?
            @borrow-after-lock-requested-thread
            @borrow-after-lock-acquired-instance-acquired?
            @borrow-after-lock-acquired-thread
            ;; just to assert that we got past the blocking calls
            (is (true? true))))))))

(deftest pool-lock-supersedes-existing-borrows-test
  (testing "if there are pending borrows when pool.lock() is called, they aren't fulfilled until after unlock()"
    (let [pool (create-populated-pool 2)
          instances (borrow-n-instances pool 2)
          blocked-borrow-thread-started? (promise)
          blocked-borrow-thread-borrowed? (promise)
          blocked-borrow-thread (future (deliver blocked-borrow-thread-started? true)
                                        (let [instance (.borrowItem pool)]
                                          (deliver blocked-borrow-thread-borrowed? true)
                                          (.releaseItem pool instance)))
          lock-thread-started? (promise)
          lock-acquired? (promise)
          unlock? (promise)
          lock-thread (future (deliver lock-thread-started? true)
                              (.lock pool)
                              (deliver lock-acquired? true)
                              @unlock?
                              (.unlock pool))]
      @blocked-borrow-thread-started?
      @lock-thread-started?
      (is (not (realized? blocked-borrow-thread-borrowed?)))
      (is (not (realized? lock-acquired?)))

      (return-instances pool instances)
      (is (not (realized? blocked-borrow-thread-borrowed?)))
      (is (not (realized? lock-thread)))
      @lock-acquired?

      (deliver unlock? true)
      @lock-thread
      @blocked-borrow-thread-borrowed?
      @blocked-borrow-thread

      ;; make sure we got here
      (is (true? true)))))

(deftest pool-lock-reentrant-test
  (testing "the thread that holds the pool lock may borrow instances while holding the lock"
    (let [pool (create-populated-pool 2)]
      (.lock pool)
      (is (true? true))
      ;; Current implementation of JRubyPool is not reentrant. This assertion
      ;; tests that the error we expect is thrown, rather than a deadlock
      ;; happening. The tests for the behavior we want have been commented out
      ;; until we change the implementation.
      (is (thrown? IllegalStateException (.borrowItem pool)))
      ;; (let [instance (.borrowItem pool)]
      ;;   (is (true? true))
      ;;   (.releaseItem pool instance))

      (is (true? true))
      (.unlock pool))))

(deftest pool-lock-reentrant-with-many-borrows-test
  (testing "the thread that holds the pool lock may borrow instances while holding the lock, even with other borrows queued"
    (let [pool (create-populated-pool 2)]
      (.lock pool)
      (is (true? true))
      (let [borrow-thread-1 (future (let [instance (.borrowItem pool)]
                                      (.releaseItem pool instance)))
            borrow-thread-2 (future (let [instance (.borrowItem pool)]
                                      (.releaseItem pool instance)))]
        ;; this is racey, but the only ways i could think of to make it non-racey
        ;; depended on knowledge of the implementation
        ;; Uncomment and make non-racey when we have an implementation that
        ;; allows reentrant borrows.
        ;; (Thread/sleep 500)
        ;; (is (not (realized? borrow-thread-1)))
        ;; (is (not (realized? borrow-thread-2)))

        ;; Current implementation of JRubyPool is not reentrant. This assertion
        ;; tests that the error we expect is thrown, rather than a deadlock
        ;; happening. The tests for the behavior we want have been commented out
        ;; until we change the implementation.
        (is (thrown? IllegalStateException (.borrowItem pool)))
        ;; (let [instance (.borrowItem pool)]
        ;;   (is (true? true))
        ;;   (.releaseItem pool instance))

        (is (true? true))
        (.unlock pool)
        @borrow-thread-1
        @borrow-thread-2))))

(deftest pool-release-item-test
  (testing (str "releaseItem call with value 'false' does not return item to "
                "pool but does allow pool to still be lockable")
    (let [pool (create-populated-pool 1)
          instance (.borrowItem pool)]
      (is (= 0 (.size pool)))
      (.releaseItem pool instance false)
      (is (= 0 (.size pool)))
      (.lock pool)
      (is (true? true))
      (.unlock pool)
      (is (true? true))))
  (testing (str "releaseItem call with value 'true' does not return item to "
                "pool and allows pool to still be lockable")
    (let [pool (create-populated-pool 1)
          instance (.borrowItem pool)]
      (is (= 0 (.size pool)))
      (.releaseItem pool instance true)
      (is (= 1 (.size pool)))
      (.lock pool)
      (is (true? true))
      (.unlock pool)
      (is (true? true)))))
