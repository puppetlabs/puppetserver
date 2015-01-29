;; TODO: this code in this namespace is copied from puppetserver, just to get things
;;  running.  It should probably be moved into a shared library or some such.
(ns puppetlabs.enterprise.ringutils
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn wrap-request-logging
  "A ring middleware that logs the request."
  [handler]
  (fn [{:keys [request-method uri] :as req}]
    (log/debug "Processing" request-method uri)
    (log/trace "---------------------------------------------------")
    (log/trace (ks/pprint-to-string (dissoc req :ssl-client-cert)))
    (log/trace "---------------------------------------------------")
    (handler req)))

(defn wrap-response-logging
  "A ring middleware that logs the response."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (log/trace "Computed response:" resp)
      resp)))