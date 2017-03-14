(ns puppetlabs.puppetserver.dashboard
  (:require [reagent.core :as reagent]
            [puppetlabs.metrics.dashboard.metrics-box :as metrics-box]
            [puppetlabs.metrics.dashboard.sortable-table :as sortable-table]
            [puppetlabs.puppetserver.metrics-data :as metrics-data]
            [puppetlabs.metrics.dashboard.utils :as metrics-utils]))

(def num-historical-data-points 60)
(def polling-interval 5000)

(defn render-metrics-boxes [metrics-fn]
  (fn []
    [:div
     [:table
      [:tbody
       [:tr
        [:td
         [metrics-box/metrics-table num-historical-data-points polling-interval
          (metrics-box/metrics-box "Free JRubies" "Current"
                                   (partial metrics-fn :num-free-jrubies))
          (metrics-box/metrics-box "Free JRubies" "Average"
                                   (partial metrics-fn :average-free-jrubies))
          (metrics-box/metrics-box "Requested JRubies" "Current"
                                   (partial metrics-fn :num-requested-jrubies))
          (metrics-box/metrics-box "Requested JRubies" "Average"
                                   (partial metrics-fn :average-requested-jrubies))
          (metrics-box/metrics-box "JRuby Borrow Time" "Average (ms)"
                                   (partial metrics-fn :average-borrow-time))
          (metrics-box/metrics-box "JRuby Wait Time" "Average (ms)"
                                   (partial metrics-fn :average-wait-time))]]
        [:td
         [metrics-box/metrics-table num-historical-data-points polling-interval
          (metrics-box/metrics-box "JVM Heap Memory Used" "Current (GB)"
                                   (partial metrics-fn :memory-used))
          (metrics-box/metrics-box "CPU Usage" "Current usage (%)"
                                   (partial metrics-fn :cpu-usage))
          (metrics-box/metrics-box "GC CPU Usage" "Current usage (%)"
                                   (partial metrics-fn :gc-cpu-usage))]]]]]]))

(defn render-lower-panel [requests-atom functions-atom resources-atom]
  (fn []
    [:table
     [:tbody
      [:tr
       [:td
        (sortable-table/sortable-table
         "Top 10 Requests"
         {:id-field :route-id
          :fields [:route-id "Route"
                   :count "Count"
                   :mean "Mean (ms)"
                   :aggregate "Aggregate (ms)"]
          :sort-field :aggregate
          :ascending false}
         requests-atom)]
       [:td
        (sortable-table/sortable-table
          "Top 10 Functions"
          {:id-field :function
           :fields [:function "Function"
                    :count "Count"
                    :mean "Mean (ms)"
                    :aggregate "Aggregate (ms)"]
           :sort-field :aggregate
           :ascending false}
          functions-atom)]
       [:td
        (sortable-table/sortable-table
          "Top 10 Resources"
          {:id-field :resource
           :fields [:resource "Resource"
                    :count "Count"
                    :mean "Mean (ms)"
                    :aggregate "Aggregate (ms)"]
           :sort-field :aggregate
           :ascending false}
          resources-atom)]]]]))

(defn init! []
  (let [requests-atom (reagent/atom {})
        functions-atom (reagent/atom {})
        resources-atom (reagent/atom {})
        metrics-data (metrics-utils/begin-metrics-loop
                       polling-interval
                       num-historical-data-points
                       (metrics-data/get-latest-metrics-data-fn
                         requests-atom
                         functions-atom
                         resources-atom))]
    (reagent/render-component
      [(render-metrics-boxes #(metrics-utils/get-values-for-metric metrics-data %))]
      (.getElementById js/document "metrics_boxes"))
    (reagent/render-component
      [(render-lower-panel requests-atom functions-atom resources-atom)]
      (.getElementById js/document "lower_panel"))))
