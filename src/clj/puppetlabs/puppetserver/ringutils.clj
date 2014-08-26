(ns puppetlabs.puppetserver.ringutils
  (:import (clojure.lang ExceptionInfo))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]))

(defn log-failed-request!
  "Logs a failed HTTP request.
  `exception` is expected to be an instance of ExceptionInfo"
  [req exception]
  {:pre [(map? req)
         (instance? ExceptionInfo exception)]}
  (log/error "===================================================")
  (log/error "Failed request:")
  (log/error (ks/pprint-to-string (dissoc req :ssl-client-cert)))
  (log/error "---------------------------------------------------")
  (log/error (.getMessage exception))
  (log/error (ks/pprint-to-string (dissoc (.getData exception) :environment)))
  (log/error "==================================================="))

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

(defn json-request?
  "Does the given request contain JSON, according to its 'Content-Type' header?
  This is copied directly out of ring.middleware.json"
  [request]
  (if-let [type (:content-type request)]
    (not (empty? (re-find #"^application/(.+\+)?json" type)))))
