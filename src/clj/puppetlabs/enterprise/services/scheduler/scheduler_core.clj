(ns puppetlabs.enterprise.services.scheduler.scheduler-core
  (:require [overtone.at-at :as at-at]
            [clojure.tools.logging :as log]))

(defn create-pool
  "Creates and returns a thread pool which can be used for scheduling jobs."
  []
  (at-at/mk-pool))

(defn wrap-with-error-logging
  "Returns a function that will invoke 'f' inside a try/catch block.  If an
  error occurs during execution of 'f', it will be logged and re-thrown."
  [f]
  (fn []
    (try
      (f)
      (catch Throwable t
        (log/error t "scheduled job threw error")
        (throw t)))))

(defn interspaced
  ; See docs on the service protocol,
  ; puppetlabs.enterprise.services.protocols.scheduler
  [n f pool]
  (let [job (wrap-with-error-logging f)]
    (-> (at-at/interspaced n job pool)
        :id  ; return only the ID; do not leak the at-at RecurringJob instance
        )))

(defn after
  ; See docs on the service protocol,
  ; puppetlabs.enterprise.services.protocols.scheduler
  [n f pool]
  (let [job (wrap-with-error-logging f)]
    (-> (at-at/after n job pool)
        :id  ; return only the ID; do not leak the at-at RecurringJob instance
        )))

(defn stop-job
  "Gracefully stops the job specified by 'id'."
  [id pool]
  (at-at/stop id pool))

(defn stop-all-jobs!
  "Stops all of the specified jobs."
  [jobs pool]
  (doseq [job jobs]
    (stop-job job pool)))
