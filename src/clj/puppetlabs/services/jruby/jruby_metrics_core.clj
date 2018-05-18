(ns puppetlabs.services.jruby.jruby-metrics-core
  (:require [schema.core :as schema]
            [puppetlabs.metrics :as metrics]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.i18n.core :refer [trs]]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol])
  (:import (com.codahale.metrics MetricRegistry Gauge Counter Histogram Meter Timer)
           (clojure.lang Atom IFn)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)
           (java.util.concurrent TimeUnit)
           (org.joda.time DateTime)
           (org.joda.time.format DateTimeFormatter)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

;; creating some constants for lock state enumeration; using Strings
;; rather than keywords since these need to be serialized over the wire
(def jruby-pool-lock-not-in-use (str :not-in-use))
(def jruby-pool-lock-requested (str :requested))
(def jruby-pool-lock-acquired (str :acquired))

(def JRubyPoolLockState
  (schema/enum jruby-pool-lock-not-in-use
               jruby-pool-lock-requested
               jruby-pool-lock-acquired))

(def JRubyLockEventType
  (schema/enum :lock-requested :lock-acquired :lock-released))

(def JRubyPoolLockStatus
  {:current-state JRubyPoolLockState
   :last-change-time schema/Str})

(def JRubyPoolLockRequestReason
  {:type (schema/eq :master-code-sync)
   :lock-request-id schema/Str})

(def JRubyMetrics
  {:num-jrubies Gauge
   :requested-count Counter
   :requested-jrubies-histo Histogram
   :borrow-count Counter
   :borrow-timeout-count Counter
   :borrow-retry-count Counter
   :return-count Counter
   :num-free-jrubies Gauge
   :free-jrubies-histo Histogram
   :borrow-timer Timer
   :wait-timer Timer
   :requested-instances Atom
   :borrowed-instances Atom
   :lock-wait-timer Timer
   :lock-held-timer Timer
   :lock-requests Atom
   :lock-status Atom
   :sampler-job-id schema/Any
   :queue-limit-hit-meter Meter})

(def TimestampedReason
  {:time Long
   :reason jruby-schemas/JRubyEventReason})

(def HttpRequestReasonInfo
  {:request {:request-method comidi/RequestMethod
             :route-id schema/Str
             :uri schema/Str}
   schema/Any schema/Any})

(def RequestReasonInfo
  (schema/conditional
   #(and (map? %) (contains? % :request)) HttpRequestReasonInfo
   :else jruby-schemas/JRubyEventReason))

(def TimestampedReasonWithRequestInfo
  (assoc TimestampedReason :reason RequestReasonInfo))

(def InstanceRequestInfo
  {:duration-millis schema/Num
   :reason RequestReasonInfo
   :time schema/Num})

(def JRubyMetricsStatusV1
  {(schema/optional-key :experimental)
   {:jruby-pool-lock-status JRubyPoolLockStatus
    :metrics {:num-jrubies schema/Int
              :num-free-jrubies schema/Int
              :requested-count schema/Int
              :borrow-count schema/Int
              :borrow-timeout-count schema/Int
              :borrow-retry-count schema/Int
              :return-count schema/Int
              :average-requested-jrubies schema/Num
              :average-free-jrubies schema/Num
              :average-borrow-time schema/Num
              :average-wait-time schema/Num
              :requested-instances [InstanceRequestInfo]
              :borrowed-instances [InstanceRequestInfo]
              :num-pool-locks schema/Int
              :average-lock-wait-time schema/Num
              :average-lock-held-time schema/Num
              :queue-limit-hit-count schema/Int
              :queue-limit-hit-rate schema/Num}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/def ^:always-validate datetime-formatter :- DateTimeFormatter
  "The date/time formatter used to produce timestamps using clj-time.
  This matches the format used by PuppetDB."
  (time-format/formatters :date-time))

(schema/defn ^:always-validate format-date-time :- schema/Str
  "Given a DateTime object, return a human-readable, formatted string."
  [date-time :- DateTime]
  (time-format/unparse datetime-formatter date-time))

(schema/defn ^:always-validate timestamp :- schema/Str
  "Returns a nicely-formatted string of the current date/time."
  []
  (format-date-time (time/now)))

(schema/defn timestamped-reason :- TimestampedReason
  [reason :- jruby-schemas/JRubyEventReason]
  {:time (System/currentTimeMillis)
   :reason reason})

(schema/defn add-duration-to-instance :- InstanceRequestInfo
  [{:keys [time] :as instance} :- TimestampedReasonWithRequestInfo]
  (assoc instance
    :duration-millis
    (- (System/currentTimeMillis) time)))

(schema/defn instance-request-info
  :- TimestampedReasonWithRequestInfo
  [instance :- TimestampedReason]
  (if-let [request (get-in instance [:reason :request])]
    (assoc-in instance [:reason :request]
              {:uri (:uri request)
               :request-method (:request-method request)
               :route-id (get-in request
                                 [:route-info :route-id]
                                 "unknown-http-request")})
    instance))

(schema/defn ^:always-validate requested-instances-info :- [InstanceRequestInfo]
  [instances :- [TimestampedReason]]
  (map
   (comp add-duration-to-instance instance-request-info)
   instances))

(schema/defn track-successful-borrow-instance!
  [{:keys [borrow-count borrowed-instances]} :- JRubyMetrics
   jruby-instance :- JRubyInstance
   reason :- jruby-schemas/JRubyEventReason]
  (.inc borrow-count)
  (let [id (:id jruby-instance)]
    (when (get @borrowed-instances id)
      (log/warn (trs "JRuby instance ''{0}'' borrowed, but it appears to have already been in use!" id)))
    (swap! borrowed-instances assoc id (timestamped-reason reason))))

(schema/defn track-request-instance!
  [{:keys [requested-count requested-instances]} :- JRubyMetrics
   {:keys [reason] :as event} :- jruby-schemas/JRubyRequestedEvent]
  (.inc requested-count)
  (swap! requested-instances assoc event (timestamped-reason reason)))

(schema/defn track-borrow-instance!
  [{:keys [borrow-timeout-count borrow-retry-count requested-instances wait-timer] :as metrics} :- JRubyMetrics
   {jruby-instance :instance requested-event :requested-event reason :reason :as event} :- jruby-schemas/JRubyBorrowedEvent]
  (condp (fn [pred instance] (pred instance)) jruby-instance
    nil? (.inc borrow-timeout-count)
    jruby-schemas/shutdown-poison-pill? (log/warn (trs "Not tracking jruby instance borrowed because server is shutting down"))
    jruby-schemas/jruby-instance? (track-successful-borrow-instance! metrics jruby-instance reason))
  (if-let [ta (get @requested-instances requested-event)]
    (do
      (.update wait-timer
        (- (System/currentTimeMillis) (:time ta))
        (TimeUnit/MILLISECONDS))
      (swap! requested-instances dissoc requested-event))
    (log/warn (trs "Unable to find request event for borrowed JRuby instance: {0}" event))))

(schema/defn track-return-instance!
  [{:keys [return-count borrowed-instances borrow-timer]} :- JRubyMetrics
   {jruby-instance :instance} :- jruby-schemas/JRubyReturnedEvent]
  (.inc return-count)
  (when (jruby-schemas/jruby-instance? jruby-instance)
    (let [id (:id jruby-instance)]
      (if-let [ta (get @borrowed-instances id)]
        (do
          (.update borrow-timer
                   (- (System/currentTimeMillis) (:time ta))
                   (TimeUnit/MILLISECONDS))
          (swap! borrowed-instances dissoc id))
        (log/warn (trs "JRuby instance ''{0}'' returned, but no record of when it was borrowed!" id))))))

(schema/defn ^:always-validate update-pool-lock-status! :- JRubyPoolLockStatus
  [jruby-pool-lock-status :- Atom
   jruby-lock-event-type :- JRubyLockEventType]
  (swap! jruby-pool-lock-status
         assoc
         :current-state (case jruby-lock-event-type
                          :lock-requested jruby-pool-lock-requested
                          :lock-acquired jruby-pool-lock-acquired
                          :lock-released jruby-pool-lock-not-in-use)
         :last-change-time (timestamp)))

(schema/defn ^:always-validate track-lock-requested!
  [{:keys [lock-requests lock-status]} :- JRubyMetrics
   {:keys [lock-request-id]} :- JRubyPoolLockRequestReason]
  (swap! lock-requests assoc
         lock-request-id
         {:state :requested
          :time (System/currentTimeMillis)})
  (update-pool-lock-status! lock-status :lock-requested))

(schema/defn ^:always-validate track-lock-acquired!
  [{:keys [lock-requests lock-status lock-wait-timer]} :- JRubyMetrics
   {:keys [lock-request-id]} :- JRubyPoolLockRequestReason]
  (if-let [lock-request (get @lock-requests lock-request-id)]
    (do
      (.update lock-wait-timer
               (- (System/currentTimeMillis) (:time lock-request))
               (TimeUnit/MILLISECONDS))
      (swap! lock-requests assoc
             lock-request-id
             {:state :acquired
              :time (System/currentTimeMillis)}))
    (log/warn (trs "Lock request ''{0}'' acquired, but no record of when it was requested!"
                   lock-request-id)))
  (update-pool-lock-status! lock-status :lock-acquired))

(schema/defn ^:always-validate track-lock-released!
  [{:keys [lock-requests lock-status lock-held-timer]} :- JRubyMetrics
   {:keys [lock-request-id]} :- JRubyPoolLockRequestReason]
  (if-let [lock-request (get @lock-requests lock-request-id)]
    (do
      (.update lock-held-timer
               (- (System/currentTimeMillis) (:time lock-request))
               (TimeUnit/MILLISECONDS))
      (swap! lock-requests dissoc lock-request-id))
    (log/warn (trs "Lock request ''{0}'' released, but no record of when it was acquired!"
                   lock-request-id)))
  (update-pool-lock-status! lock-status :lock-released))

(schema/defn track-free-instance-count!
  [metrics :- JRubyMetrics
   free-instance-count :- schema/Int]
  (.update (:free-jrubies-histo metrics) free-instance-count))

(schema/defn track-requested-instance-count!
  [{:keys [requested-jrubies-histo requested-instances]} :- JRubyMetrics]
  (.update requested-jrubies-histo (count @requested-instances)))

(schema/defn sample-jruby-metrics!
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   metrics :- JRubyMetrics]
  (log/trace (trs "Sampling JRuby metrics"))
  (track-free-instance-count!
   metrics
   (jruby-protocol/free-instance-count jruby-service))
  (track-requested-instance-count! metrics))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate init-metrics :- JRubyMetrics
  [hostname :- schema/Str
   max-active-instances :- schema/Int
   free-instances-fn :- IFn
   registry :- MetricRegistry]
  {:num-jrubies (metrics/register registry (metrics/host-metric-name hostname "jruby.num-jrubies")
                  (metrics/gauge max-active-instances))
   :requested-count (.counter registry (metrics/host-metric-name hostname "jruby.request-count"))
   ;; See the comments on `jruby-metrics-service/schedule-metrics-sampler!` for
   ;; an explanation of how we're using these histograms.
   :requested-jrubies-histo (.histogram registry (metrics/host-metric-name hostname "jruby.requested-jrubies-histo"))
   :borrow-count (.counter registry (metrics/host-metric-name hostname "jruby.borrow-count"))
   :borrow-timeout-count (.counter registry (metrics/host-metric-name hostname "jruby.borrow-timeout-count"))
   :borrow-retry-count (.counter registry (metrics/host-metric-name hostname "jruby.borrow-retry-count"))
   :return-count (.counter registry (metrics/host-metric-name hostname "jruby.return-count"))
   :num-free-jrubies (metrics/register registry (metrics/host-metric-name hostname "jruby.num-free-jrubies")
                   (proxy [Gauge] []
                     (getValue []
                       (free-instances-fn))))
   ;; See the comments on `jruby-metrics-service/schedule-metrics-sampler!` for
   ;; an explanation of how we're using these histograms.
   :free-jrubies-histo (.histogram registry (metrics/host-metric-name hostname "jruby.free-jrubies-histo"))
   :borrow-timer (.timer registry (metrics/host-metric-name hostname "jruby.borrow-timer"))
   :wait-timer (.timer registry (metrics/host-metric-name hostname "jruby.wait-timer"))
   :requested-instances (atom {})
   :borrowed-instances (atom {})
   :lock-wait-timer (.timer registry (metrics/host-metric-name hostname "jruby.lock-wait-timer"))
   :lock-held-timer (.timer registry (metrics/host-metric-name hostname "jruby.lock-held-timer"))
   :lock-requests (atom {})
   :lock-status (atom {:current-state jruby-pool-lock-not-in-use
                       :last-change-time (timestamp)})
   :sampler-job-id nil
   :queue-limit-hit-meter (.meter registry (metrics/host-metric-name hostname "jruby.queue-limit-hit-meter"))})

(schema/defn jruby-event-callback
  [metrics :- JRubyMetrics
   event :- jruby-schemas/JRubyEvent]
  (if-let [[func & args] (case (:type event)
                           :instance-requested [track-request-instance! metrics event]
                           :instance-borrowed [track-borrow-instance! metrics event]
                           :instance-returned [track-return-instance! metrics event]
                           :lock-requested [track-lock-requested! metrics (:reason event)]
                           :lock-acquired [track-lock-acquired! metrics (:reason event)]
                           :lock-released [track-lock-released! metrics (:reason event)]
                           nil)]
    (try
      (apply func args)
      (catch Exception e
        (log/error e (trs "Error ocurred while recording metrics for jruby event type: {0}" (:type event)))
        (throw e)))
    (throw (IllegalStateException. (trs "Unrecognized jruby event type: {0}" (:type event))))))

(schema/defn ^:always-validate v1-status :- status-core/StatusCallbackResponse
  [metrics :- JRubyMetrics
   level :- status-core/ServiceStatusDetailLevel]
  (let [{:keys [num-jrubies requested-count requested-jrubies-histo
                borrow-count borrow-timeout-count borrow-retry-count
                return-count free-jrubies-histo num-free-jrubies borrow-timer
                wait-timer requested-instances borrowed-instances lock-status
                lock-wait-timer lock-held-timer queue-limit-hit-meter]} metrics
        level>= (partial status-core/compare-levels >= level)]
    {:state :running
     :status (cond->
              ;; no status info at ':critical' level
              {}
              ;; no extra status at ':info' level yet
              (level>= :info) identity
              (level>= :debug) (assoc
                                 :experimental
                                 {:jruby-pool-lock-status @lock-status
                                  :metrics
                                  {:num-jrubies (.getValue num-jrubies)
                                   :num-free-jrubies (.getValue num-free-jrubies)
                                   :requested-count (.getCount requested-count)
                                   :borrow-count (.getCount borrow-count)
                                   :borrow-timeout-count (.getCount borrow-timeout-count)
                                   :borrow-retry-count (.getCount borrow-retry-count)
                                   :return-count (.getCount return-count)
                                   :average-requested-jrubies (metrics/mean requested-jrubies-histo)
                                   :average-free-jrubies (metrics/mean free-jrubies-histo)
                                   :average-borrow-time (metrics/mean-millis borrow-timer)
                                   :average-wait-time (metrics/mean-millis wait-timer)
                                   :requested-instances (requested-instances-info (vals @requested-instances))
                                   :borrowed-instances (requested-instances-info (vals @borrowed-instances))
                                   :num-pool-locks (.getCount lock-held-timer)
                                   :average-lock-wait-time (metrics/mean-millis lock-wait-timer)
                                   :average-lock-held-time (metrics/mean-millis lock-held-timer)
                                   :queue-limit-hit-count (.getCount queue-limit-hit-meter)
                                   :queue-limit-hit-rate (.getFiveMinuteRate queue-limit-hit-meter)
                                   }}))}))

;; This function schedules some metrics sampling to happen on a background thread.
;; The reason it is necessary to do this is because the metrics histograms are
;; sample-based, as opposed to time-based, and we are interested in keeping a
;; time-based average for certain metrics.  e.g. if we only updated the
;; "free-instance-count" average when an instance was borrowed or returned, then,
;; if there was a period where there was no load on the server, the histogram
;; would not be getting any updates at all and the average would appear to
;; remain flat, when actually it should be changing (increasing, presumably,
;; because there should be plenty of free jruby instances available in the pool).
(schema/defn ^:always-validate schedule-metrics-sampler!
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   metrics :- JRubyMetrics
   interspaced :- IFn]
  (interspaced 5000 (partial sample-jruby-metrics! jruby-service metrics)))
