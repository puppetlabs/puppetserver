(ns puppetlabs.enterprise.services.scheduler.scheduler-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.enterprise.services.scheduler.scheduler-service :refer :all]
            [puppetlabs.enterprise.services.protocols.scheduler :refer :all]
            [puppetlabs.trapperkeeper.app :as tk]
            [overtone.at-at :as at-at]))

(deftest ^:integration test-interspaced
  (with-app-with-empty-config app [scheduler-service]
    (testing "interspaced"
      (let [service (tk/get-service app :SchedulerService)
            num-runs 3 ; let it run a few times, but not too many
            interval 300
            p (promise)
            counter (atom 0)
            delays (atom [])
            last-completion-time (atom nil)
            job (fn []
                  (when @last-completion-time
                    (let [delay (- (System/currentTimeMillis) @last-completion-time)]
                      (swap! delays conj delay)))
                  (swap! counter inc)

                  ; Make this job take a while so we can measure the duration
                  ; between invocations and ensure that the next invocation is
                  ; not scheduled until this one completes.
                  (Thread/sleep 100)

                  ; The test is over!
                  (when (= @counter num-runs)
                    (deliver p nil))

                  (reset! last-completion-time (System/currentTimeMillis)))]

        ; Schedule the job, and wait for it run num-runs times, then stop it.
        (let [job-id (interspaced service interval job)]
          (deref p)
          (stop-job service job-id))

        (testing (str "Each delay should be at least " interval "ms")
          (is (every? (fn [delay] (>= delay interval)) @delays)))))))

(deftest ^:integration test-after
  (with-app-with-empty-config app [scheduler-service]
    (testing "after"
      (let [delay 100]
        (testing "should execute at least " delay " milliseconds in the future"
          (let [completed (promise)
                job #(deliver completed (System/currentTimeMillis))
                service (tk/get-service app :SchedulerService)]
            (let [schedule-time (System/currentTimeMillis)]
              (after service delay job)
              (let [execution-time (deref completed)
                    actual-delay (- execution-time schedule-time)]
                (is (>= actual-delay delay))))))))))

; This test has a race condition, but it is very unlikley to occur in reality,
; and so far it's actually been impossible to get this test to fail due to a
; lost race.  'stop-job' is probably impossible to test deterministically.
; It was decided that having a test with a race condition was better than no test
; at all in this case, primarily due to the fact that the underlying scheduler
; library (at-at) has no tests of its own.  :(
(deftest ^:integration test-stop-job
  (testing "stop-job lets a job complete but does not run it again"
    (with-app-with-empty-config app [scheduler-service]
      (let [service (tk/get-service app :SchedulerService)
            started (promise)
            stopped (promise)
            start-time (atom 0)
            completed (promise)
            job (fn []
                  (reset! start-time (System/currentTimeMillis))
                  (deliver started nil)
                  (deref stopped)
                  (deliver completed nil))
            job-id (interspaced service 10 job)]
        ; wait for the job to start
        (deref started)
        (let [original-start-time @start-time]
          (testing "the job can be stopped"
            (is (stop-job service job-id)))
          (deliver stopped nil)
          (deref completed)
          ; wait a bit, ensure the job does not run again
          (testing "the job should not run again"
            (Thread/sleep 100)
            (is (= original-start-time @start-time))))))))

(defn schedule-random-jobs
  "Schedules several random jobs and returns their IDs."
  [service]
  (set
    (for [x [1 2 3]]
      (interspaced service 1000 (constantly x)))))

; In the future, it might be reasonable to add a function like this into the
; scheduler service protocol.  If so, this function can be deleted.
(defn at-at-scheduled-jobs
  "Returns all of at-at's scheduled jobs."
  [service]
  (set (map :id (at-at/scheduled-jobs (get-pool service)))))

(deftest ^:integration test-shutdown
  (testing "Any remaining jobs will be stopped when the service is stopped."
    (let [app (bootstrap-services-with-empty-config [scheduler-service])
          service (tk/get-service app :SchedulerService)
          job-ids (schedule-random-jobs service)]

      (testing "at-at reports all of the jobs we just scheduled"
        (is (= job-ids (at-at-scheduled-jobs service))))

      (testing "Stopping the service stops all of the scheduled jobs"
        (tk/stop app)
        (is (= #{} (at-at-scheduled-jobs service)))))))
