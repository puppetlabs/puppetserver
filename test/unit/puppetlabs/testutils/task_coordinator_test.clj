(ns puppetlabs.testutils.task-coordinator-test
  (:require [clojure.test :refer :all]
            [puppetlabs.testutils.task-coordinator :as tc]))

(deftest task-coordinator-basic-test
  (let [phases [:phase1 :phase2 :phase3]
        coordinator (tc/task-coordinator phases)]
    (testing "basic life cycle coordination"
      (let [task1-current-phase (atom :not-started)
            task2-current-phase (atom :not-started)
            task-fn (fn [task-id current-phase-atom]
                      (reset! current-phase-atom :phase1)
                      (tc/notify-task-progress coordinator task-id :phase1)
                      (reset! current-phase-atom :phase2)
                      (tc/notify-task-progress coordinator task-id :phase2)
                      (reset! current-phase-atom :phase3)
                      (tc/notify-task-progress coordinator task-id :phase3)
                      (reset! current-phase-atom :finished)
                      task-id)]
        (testing "task isn't started if initialized without phase"
          (tc/initialize-task coordinator :task1
                              (partial task-fn :task1 task1-current-phase))
          (is (= :not-started @task1-current-phase)))
        (testing "task is advanced to given phase if initialized with phase"
          (tc/initialize-task coordinator :task2
                              (partial task-fn :task2 task2-current-phase)
                              :phase1)
          (is (= :phase1 @task2-current-phase)))
        (testing "advance-task-to"
          (tc/advance-task-to coordinator :task1 :phase1)
          (is (= :phase1 @task1-current-phase))
          (is (= :phase1 @task2-current-phase))
          (tc/advance-task-to coordinator :task1 :phase2)
          (is (= :phase2 @task1-current-phase))
          (is (= :phase1 @task2-current-phase))
          (tc/advance-task-to coordinator :task2 :phase3)
          (is (= :phase2 @task1-current-phase))
          (is (= :phase3 @task2-current-phase)))
        (testing "final-response"
          (is (= :task1 (tc/final-result coordinator :task1)))
          (is (= :finished @task1-current-phase))
          (is (= :phase3 @task2-current-phase))
          (is (= :task2 (tc/final-result coordinator :task2)))
          (is (= :finished @task2-current-phase)))))))

(deftest task-coordinator-async-test
  (let [phases [:phase1 :phase2]
        coordinator (tc/task-coordinator phases)]
    (testing "async life cycle coordination"
      (let [task1-current-phase (atom :not-started)
            phase2-promise (promise)
            callback-promise (promise)
            task-fn (fn [task-id current-phase-atom]
                      (reset! current-phase-atom :phase1)
                      (tc/notify-task-progress coordinator task-id :phase1)
                      @phase2-promise
                      (reset! current-phase-atom :phase2)
                      (tc/notify-task-progress coordinator task-id :phase2)
                      (reset! current-phase-atom :finished)
                      task-id)]
        (tc/initialize-task coordinator :task1
                            (partial task-fn :task1 task1-current-phase))

        (tc/callback-at-phase coordinator :task1 :phase1
                              (fn [task-id phase]
                                (deliver callback-promise true)))

        (tc/unblock-task-to coordinator :task1 :phase2)

        ;; unblocking through phase2 should cause the phase1 callback to
        ;; be called, so we wait for the callback promise to be delivered
        @callback-promise

        ;; the task should be waiting for us to deliver the phase2-promise,
        ;; so it should be at phase1.
        (is (= :phase1 @task1-current-phase))

        (deliver phase2-promise true)

        (tc/wait-for-task coordinator :task1 :phase2)
        (is (= :phase2 @task1-current-phase))

        (is (= :task1 (tc/final-result coordinator :task1)))))))

