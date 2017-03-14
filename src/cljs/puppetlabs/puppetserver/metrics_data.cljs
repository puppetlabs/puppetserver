(ns puppetlabs.puppetserver.metrics-data
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs-http.client :as http]
            [cljs.core.async :as async]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def table-size
  "Number of rows to show in each of the metrics tables"
  10)

(def gigabyte
  "Number of bytes in a gigabyte"
  (* 1024 1024 1024))

(defn bytes->gigabytes
  "Convert a value in bytes to gigabytes"
  [number]
  (/ number gigabyte))

(defn extract-metrics-from-status
  [request-atom functions-atom resources-atom status-response]
  #_(println "Full response from server:" status-response)
  (let [request-metrics (take table-size (get-in status-response [:master :status :experimental :http-metrics]))]
    (reset! request-atom request-metrics))
  (let [function-metrics (take table-size (get-in status-response [:puppet-profiler :status :experimental :function-metrics]))]
    (reset! functions-atom function-metrics))
  (let [resource-metrics (take table-size (get-in status-response [:puppet-profiler :status :experimental :resource-metrics]))]
    (reset! resources-atom resource-metrics))
  (let [jruby-metrics (get-in status-response [:jruby-metrics :status :experimental :metrics])
        jvm-metrics (get-in status-response [:status-service :status :experimental :jvm-metrics])]
    {:num-free-jrubies (:num-free-jrubies jruby-metrics)
     :average-free-jrubies (:average-free-jrubies jruby-metrics)
     :num-requested-jrubies (count (:requested-instances jruby-metrics))
     :average-requested-jrubies (:average-requested-jrubies jruby-metrics)
     :average-borrow-time (:average-borrow-time jruby-metrics)
     :average-wait-time (:average-wait-time jruby-metrics)
     :memory-used (bytes->gigabytes (get-in jvm-metrics [:heap-memory :used]))
     :cpu-usage (:cpu-usage jvm-metrics)
     :gc-cpu-usage (:gc-cpu-usage jvm-metrics)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-latest-metrics-data-fn
  [request-atom functions-atom resources-atom]
  (fn [channel]
    (go
      ;; TODO: don't hard-code this URL
      (let [response (async/<! (http/get "/status/v1/services?level=debug"))]
        (if-not (= 200 (:status response))
          (do
            ;; TODO: better error handling
            (.log js/console (str "Error issuing HTTP request!: " (:status response) " " (:body response)))
            (async/close! channel))
          (let [metrics-data (extract-metrics-from-status
                               request-atom
                               functions-atom
                               resources-atom
                               (:body response))]
            (async/put! channel metrics-data)))))))
