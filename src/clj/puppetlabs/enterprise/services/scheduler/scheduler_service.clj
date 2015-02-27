(ns puppetlabs.enterprise.services.scheduler.scheduler-service
  (:require [puppetlabs.trapperkeeper.services :as tk]
            [puppetlabs.enterprise.services.protocols.scheduler :refer :all]
            [puppetlabs.enterprise.services.scheduler.scheduler-core :as core]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Internal "helper" functions

(defn get-pool
  [this]
  (-> this
      (tk/service-context)
      :pool))

(defn get-jobs
  [this]
  (-> this
      (tk/service-context)
      :jobs))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Trapperkeeper service definition
(tk/defservice scheduler-service
  SchedulerService
  []

  (init [this context]
    (let [pool (core/create-pool)]
      (assoc context :pool pool
                     :jobs (atom #{}))))

  (stop [this context]
    ; Stop any jobs that are still running
    (core/stop-all-jobs! @(:jobs context) (get-pool this))
    context)

  (interspaced [this n f]
    (let [id (core/interspaced n f (get-pool this))]
      (swap! (get-jobs this) conj id)
      id))

  (after [this n f]
    (let [id (core/after n f (get-pool this))]
      (swap! (get-jobs this) conj id)
      id))

  (stop-job [this id]
    (let [result (core/stop-job id (get-pool this))]
      (when result
        (swap! (get-jobs this) disj id))
      result)))
