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
    (stop-job job pool))

  ; Shutdown at-at's thread pool.  This is the only way to do it, which is
  ; unfortunate because it also resets the thread pool.  It's possible to
  ; hack around this via ...
  ;
  ;   (-> pool
  ;       :pool-atom
  ;       (deref)
  ;       :thread-pool
  ;       (.shutdown))
  ;
  ; ... but that is a horrible hack.  I've opened an issue with at-at to add a
  ; function that can be called to just stop the pool and not also reset it -
  ; https://github.com/overtone/at-at/issues/13
  (at-at/stop-and-reset-pool! pool))
