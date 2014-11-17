(ns puppetlabs.puppetserver.ringutils
  (:import (clojure.lang ExceptionInfo)
           (java.security.cert X509Certificate))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.certificate-authority.core :as ca-utils]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def WhitelistSettings
  {(schema/optional-key :authorization-required) schema/Bool
   :client-whitelist [schema/Str]})

(def RingRequest
  {:uri schema/Str
   (schema/optional-key :ssl-client-cert) X509Certificate
   schema/Keyword schema/Any})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn get-cert-subject
  "Pull the common name of the subject off the certificate."
  [certificate]
  (-> certificate
      (ca/get-subject)
      (ca-utils/x500-name->CN)))

(defn log-access-denied
  [uri certificate]
  "Log a message to info stating that the client is not in the
   access control whitelist."
  (let [subject (get-cert-subject certificate)]
    (log/info
      (str "Client '" subject "' access to" uri "rejected;\n"
           "client not found in whitelist configuration."))))

(defn client-on-whitelist?
  "Test if the certificate subject is on the client whitelist."
  [settings certificate]
  (let [whitelist (-> settings
                      :client-whitelist
                      (set))
        client    (get-cert-subject certificate)]
    (contains? whitelist client)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

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

(schema/defn client-allowed-access? :- schema/Bool
  "Determines if the client in the request is allowed to access the
   endpoint based on the client whitelist and
   whether authorization is required."
  [settings :- WhitelistSettings
   req :- RingRequest]
  (if (get settings :authorization-required true)
    (if-let [client-cert (:ssl-client-cert req)]
      (if (client-on-whitelist? settings client-cert)
        true
        (do (log-access-denied (:uri req) client-cert) false))
      (do
        (log/info "Access to certificate_status rejected; no client certificate found")
        false))
    true))
