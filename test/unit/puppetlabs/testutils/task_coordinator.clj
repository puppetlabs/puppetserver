(ns puppetlabs.testutils.task-coordinator
  "This namespace defines a TaskCoordinator protocol.  It is used for testing
  in cases where you need a lot of control over asynchronous behaviors; it provides
  a mechanism for defining phases for a task, and blocking/unblocking the task
  at any phase of its life cycle."
  (:require [schema.core :as schema]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async])
  (:import (clojure.lang IFn)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private

(defprotocol TaskCoordinator
  (initialize-task
    [this task-id task-fn]
    [this task-id task-fn phase]
    "Initialize a task given an id and a function that will actually perform
    the task.  Also supports an optional `phase` keyword; if not provided,
    then the task will be blocked without having started yet.  If `phase`
    is provided, the task will be executed synchronously until it reaches
    the specified `phase` (which must be a valid phase from `task-phases`).")
  (notify-task-progress [this task-id phase]
    "Notify the coordinator that the task with the given ID has reached the
    given phase.  This function should be called from within the logic of the
    task itself, and will cause the task to block until the coordinator
    has given it permission to proceed.")
  (advance-task-to [this task-id phase]
    "Synchronously advance the task with the given id to the specified phase.")
  (unblock-task-to [this task-id phase]
    "Allow the task with the given id to proceed, asynchronously, to the
    specified phase.  This call is asynchronous and returns immediately; it
    should always be followed by a call to `wait-for-task` to re-join the
    threads after some intermediate action.")
  (wait-for-task [this task-id phase]
    "Blocks until the task with the given id reaches the specified phase.  This
    should only be called following a call to `unblock-task-to`.")
  (callback-at-phase [this task-id phase callback]
    "Register a callback function that should be called when a task reaches
    a certain phase.  Used in combination with `unblock-task-to`.  The callback
    function should accept two arguments: a task-id and a phase.")
  (final-result [this task-id]
    "Synchronously advance the task through to completion (if it has not
    already been completed), and return the result of the original
    `task-fn` that was passed in to `initialize-task`.")

  (task-phases [this]
    "For internal use; the sequence of phases making up the life cycle for a
    task managed by this coordinator.")
  (tasks [this]
    "For internal use; the state of all of the tasks currently being managed
    by this coordinator."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private

(schema/defn wait-for-ack
  [channel :- (schema/protocol async-protocols/Channel)]
  (let [ack (async/<!! channel)]
    (when-not (= ack :go)
      (let [msg (format "Expected an ack of :go, got: '%s'" ack)]
        (log/error msg)
        (throw (IllegalStateException. msg))))))

(schema/defn ^:always-validate do-initialize-task*
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- schema/Str
   req-fn :- IFn
   phase :- (schema/maybe schema/Keyword)]
  (when-let [existing-task (get @(tasks coordinator) task-id)]
    (async/close! (:channel existing-task)))
  (let [task-channel (async/chan)
        result-channel (async/go
                           (log/debug "Blocking for initial task ack:" task-id)
                           (wait-for-ack task-channel)
                           (log/debug "Calling task fn:" task-id)
                           (let [resp (req-fn)]
                             (log/debug "Finished task fn:" task-id resp)
                             resp))]
    (swap! (tasks coordinator)
           assoc task-id {:channel task-channel
                          :current-phase nil
                          :remaining-phases (task-phases coordinator)
                          :result-channel result-channel})
    (when phase
      (log/debug "Waiting for initialized task to reach phase:" task-id phase)
      (advance-task-to coordinator task-id phase))
    (log/debug "Done initializing task:" task-id)))

(schema/defn ^:always-validate do-initialize-task
  ([coordinator :- (schema/protocol TaskCoordinator)
    task-id :- (schema/either schema/Str schema/Int schema/Keyword)
    req-fn :- IFn]
   (do-initialize-task coordinator task-id req-fn nil))
  ([coordinator :- (schema/protocol TaskCoordinator)
    task-id :- (schema/either schema/Str schema/Int schema/Keyword)
    req-fn :- IFn
    phase :- (schema/maybe schema/Keyword)]
   (do-initialize-task* coordinator (str task-id) req-fn phase)))

(schema/defn ^:always-validate do-notify-task-progress*
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- schema/Str
   phase :- schema/Keyword]
  (let [channel (:channel (get @(tasks coordinator) task-id))]
    (log/debug "Notifying task progress:" task-id phase)
    (async/>!! channel {:task-id task-id
                        :phase phase})
    (log/debug "Blocking task; waiting for ack:" task-id phase)
    (wait-for-ack channel)
    (log/debug "task received ack, unblocking:" task-id phase)))

(schema/defn ^:always-validate do-notify-task-progress
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- (schema/either schema/Str schema/Int schema/Keyword)
   phase :- schema/Keyword]
  (do-notify-task-progress* coordinator (str task-id) phase))

(schema/defn ^:always-validate do-advance-task-to*
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- schema/Str
   desired-phase :- schema/Keyword]
  (log/debug "Advancing task to phase:" task-id desired-phase)
  (let [channel (:channel (get @(tasks coordinator) task-id))
        next-phase (fn [] (:phase (async/<!! channel)))]
    (log/debug "Advancing task: sending ack" task-id)
    (async/>!! channel :go)
    (loop [phase (next-phase)]
      (log/debug "Advancing task; reached phase:" task-id phase)
      (let [task (get @(tasks coordinator) task-id)
            next-expected-phase (first (:remaining-phases task))]
        (when-not (= next-expected-phase phase)
          (let [msg (format "Expected next phase '%s', got '%s'" next-expected-phase phase)]
            (log/error msg)
            (throw (IllegalStateException. msg))))
        (let [remaining-phases (rest (:remaining-phases task))]
          (log/debug "Advancing task; remaining phases:" task-id remaining-phases)
          (swap! (tasks coordinator) assoc-in [task-id :remaining-phases] remaining-phases))
        (swap! (tasks coordinator) assoc-in [task-id :current-phase] phase)
        (if (= desired-phase phase)
          (log/debug "task has reached desired phase:" task-id phase)
          (do
            (log/debug "Advancing task: sending ack" task-id)
            (async/>!! (:channel task) :go)
            (recur (next-phase))))))))

(schema/defn ^:always-validate do-advance-task-to
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- (schema/either schema/Str schema/Int schema/Keyword)
   desired-phase :- schema/Keyword]
  (do-advance-task-to* coordinator (str task-id) desired-phase))

(schema/defn ^:always-validate do-advance-task-through-all-phases*
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- schema/Str]
  (let [task (get @(tasks coordinator) task-id)]
    (when-not (empty? (:remaining-phases task))
      (log/debug "Advancing task to last phase:" task-id (last (task-phases coordinator)))
      (do-advance-task-to coordinator task-id (last (task-phases coordinator))))
    (log/debug "Advancing task to completion:" task-id)
    (async/>!! (:channel task) :go)))

(schema/defn ^:always-validate do-advance-task-through-all-phases
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- (schema/either schema/Str schema/Int schema/Keyword)]
  (do-advance-task-through-all-phases* coordinator (str task-id)))

(schema/defn ^:always-validate do-unblock-task-to
  ([coordinator :- (schema/protocol TaskCoordinator)
    task-id :- (schema/either schema/Str schema/Int schema/Keyword)
    desired-phase :- schema/Keyword]
   (do-unblock-task-to coordinator task-id desired-phase nil))
  ([coordinator :- (schema/protocol TaskCoordinator)
    task-id :- (schema/either schema/Str schema/Int schema/Keyword)
    desired-phase :- schema/Keyword
    callback :- (schema/maybe IFn)]
   (log/debug "Unblocking task to phase:" task-id desired-phase)
   (async/go (advance-task-to coordinator task-id desired-phase)
             (when callback
               (callback task-id desired-phase)))))

(schema/defn ^:always-validate do-wait-for-task*
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- schema/Str
   desired-phase :- schema/Keyword]
  (log/debug "Waiting for task to reach phase:" task-id desired-phase)
  (loop [task (get @(tasks coordinator) task-id)]
    (when-not (= desired-phase (:current-phase task))
      (recur (get @(tasks coordinator) task-id)))))

(schema/defn ^:always-validate do-wait-for-task
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- (schema/either schema/Str schema/Int schema/Keyword)
   desired-phase :- schema/Keyword]
  (do-wait-for-task* coordinator (str task-id) desired-phase))

(schema/defn ^:always-validate do-callback-at-phase
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- (schema/either schema/Str schema/Int schema/Keyword)
   desired-phase :- schema/Keyword
   callback :- IFn]
  (async/go (do-wait-for-task coordinator task-id desired-phase)
            (callback task-id desired-phase)))

(schema/defn ^:always-validate do-final-result*
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- schema/Str]
  (log/debug "Retrieving final result:" task-id)
  (let [task (get @(tasks coordinator) task-id)]
    (do-advance-task-through-all-phases coordinator task-id)
    (log/debug "Reading final result:" task-id)
    (let [result (async/<!! (:result-channel task))]
      (log/debug "Read final result:" task-id result)
      (async/close! (:result-channel task))
      (async/close! (:channel task))
      (swap! (tasks coordinator) dissoc task-id)
      result)))

(schema/defn ^:always-validate do-final-result
  [coordinator :- (schema/protocol TaskCoordinator)
   task-id :- (schema/either schema/Str schema/Int schema/Keyword)]
  (do-final-result* coordinator (str task-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(schema/defn ^:always-validate task-coordinator :- (schema/protocol TaskCoordinator)
  "Creates a `TaskCoordinator` for coordinating tasks whose life cycle is
  defined by the phases in `task-phases`."
  [task-phases :- [schema/Keyword]]
  (let [tasks (atom {})]
    (reify
      TaskCoordinator
      (initialize-task [this task-id task-fn] (do-initialize-task this task-id task-fn))
      (initialize-task [this task-id task-fn phase] (do-initialize-task this task-id task-fn phase))
      (notify-task-progress [this task-id phase] (do-notify-task-progress this task-id phase))
      (advance-task-to [this task-id phase] (do-advance-task-to this task-id phase))
      (unblock-task-to [this task-id phase] (do-unblock-task-to this task-id phase))
      (wait-for-task [this task-id phase] (do-wait-for-task this task-id phase))
      (callback-at-phase [this task-id phase callback] (do-callback-at-phase this task-id phase callback))
      (final-result [this task-id] (do-final-result this task-id))

      ;; It might be better to break this into a record/type plus a protocol;
      ;; Then we could hide things like the `tasks` and `task-phases` in
      ;; the type and not expose as part of the protocol API.
      (task-phases [this] task-phases)
      (tasks [this] tasks))))
