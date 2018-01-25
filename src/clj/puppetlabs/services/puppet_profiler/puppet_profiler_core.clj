(ns puppetlabs.services.puppet-profiler.puppet-profiler-core
  (:import (com.codahale.metrics MetricRegistry Timer)
           (com.puppetlabs.puppetserver MetricsPuppetProfiler PuppetProfiler))
  (:require [clojure.string :as str]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.metrics :as metrics]
            [puppetlabs.i18n.core :refer [trs]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def MetricsProfilerServiceContext
  {:profiler (schema/maybe PuppetProfiler)})

(def MetricsProfilerServiceConfig
  {(schema/optional-key :enabled)
   (schema/conditional string? schema/Str :else schema/Bool)})

(def PuppetProfilerStatusV1
  {(schema/optional-key :experimental)
   {:function-metrics [{:function schema/Str
                        :count schema/Int
                        :mean schema/Num
                        :aggregate schema/Num}]
    :resource-metrics [{:resource schema/Str
                        :count schema/Int
                        :mean schema/Num
                        :aggregate schema/Num}]
    :catalog-metrics [{:metric schema/Str
                       :count schema/Int
                       :mean schema/Num
                       :aggregate schema/Num}]
    :puppetdb-metrics [{:metric schema/Str
                        :count schema/Int
                        :mean schema/Num
                        :aggregate schema/Num}]
    :inline-metrics [{:metric schema/Str
                      :count schema/Int}]}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn metrics-profiler :- PuppetProfiler
  [hostname :- String
   registry :- MetricRegistry]
  (MetricsPuppetProfiler. hostname registry))

(defn function-metric
  [[function-name timer]]
  (let [count (.getCount timer)
        mean (metrics/mean-millis timer)]
    {:function  function-name
     :count     count
     :mean      mean
     :aggregate (* count mean)}))

(defn resource-metric
  [[resource-name timer]]
  (let [count (.getCount timer)
        mean (metrics/mean-millis timer)]
    {:resource  resource-name
     :count     count
     :mean      mean
     :aggregate (* count mean)}))

(defn catalog-metric
  [[metric-name timer]]
  (let [count (.getCount timer)
        mean (metrics/mean-millis timer)]
    {:metric metric-name
     :count count
     :mean mean
     :aggregate (* count mean)}))

(defn puppetdb-metric
  [[metric-name timer]]
  (let [count (.getCount timer)
        mean (metrics/mean-millis timer)
        ;; Metric databases like Graphite and InfluxDB treat
        ;; spaces and periods as delimiters.
        ;;
        ;; TODO: Should probably be abstracted into
        ;; trapperkeeper-metrics as a general utility function.
        munged-name (str/replace metric-name #"\.| " "_")]
    {:metric munged-name
     :count count
     :mean mean
     :aggregate (* count mean)}))

(defn inline-metric
  [[metric-name timer]]
  (let [count (.getCount timer)]
    {:metric metric-name
     :count count}))

(schema/defn assoc-metrics-data
  [status :- {schema/Any schema/Any}
   metrics-profiler :- (schema/maybe MetricsPuppetProfiler)]
  (if metrics-profiler
    (let [function-metrics (->> (map function-metric (.getFunctionTimers metrics-profiler))
                                (sort-by :aggregate)
                                reverse)
          resource-metrics (->> (map resource-metric (.getResourceTimers metrics-profiler))
                                (sort-by :aggregate)
                                reverse)
          catalog-metrics (->> (map catalog-metric (.getCatalogTimers metrics-profiler))
                               (sort-by :aggregate)
                               reverse)
          puppetdb-metrics (->> (map puppetdb-metric (.getPuppetDBTimers metrics-profiler))
                                (sort-by :aggregate)
                                reverse)
          inline-metrics (->> (map inline-metric (.getInliningTimers metrics-profiler))
                               (sort-by :count)
                               reverse)]
      (-> status
          (assoc-in [:experimental :function-metrics] (take 50 function-metrics))
          (assoc-in [:experimental :resource-metrics] (take 50 resource-metrics))
          (assoc-in [:experimental :catalog-metrics] catalog-metrics)
          (assoc-in [:experimental :puppetdb-metrics] puppetdb-metrics)
          (assoc-in [:experimental :inline-metrics] inline-metrics)))
    status))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate v1-status :- status-core/StatusCallbackResponse
  [metrics-profiler :- (schema/maybe MetricsPuppetProfiler)
   level :- status-core/ServiceStatusDetailLevel]
  (let [level>= (partial status-core/compare-levels >= level)]
    {:state :running
     :status (cond->
               ;; no status info at ':critical' level
               {}
               ;; no extra status at ':info' level yet
               (level>= :info) identity
               (level>= :debug) (assoc-metrics-data metrics-profiler))}))

(schema/defn ^:always-validate initialize :- MetricsProfilerServiceContext
  [config :- MetricsProfilerServiceConfig
   hostname :- schema/Str
   registry :- (schema/maybe MetricRegistry)]
  (let [enabled (if (some? (:enabled config))
                  (ks/to-bool (:enabled config))
                  true)]
    (if enabled
      {:profiler (metrics-profiler hostname registry)}
      {:profiler nil})))
