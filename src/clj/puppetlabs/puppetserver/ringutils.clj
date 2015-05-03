(ns puppetlabs.puppetserver.ringutils
  (:import (clojure.lang IFn)
           (java.security.cert X509Certificate))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [ring.util.response :as ring]
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
      (ssl-utils/x500-name->CN)))

(defn log-access-denied
  [uri certificate]
  "Log a message to info stating that the client is not in the
   access control whitelist."
  (let [subject (get-cert-subject certificate)]
    (log/info
      (str "Client '" subject "' access to " uri " rejected;\n"
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
        (log/info "Access to " (:uri req) " rejected; no client certificate found")
        false))
    true))

(schema/defn ^:always-validate
  wrap-with-cert-whitelist-check :- IFn
  "A ring middleware that checks to make sure the client cert is in the whitelist
  before granting access."
  [handler :- IFn
   settings :- WhitelistSettings]
  (fn [req]
    (if (client-allowed-access? settings req)
      (handler req)
      {:status 401 :body "Unauthorized"})))

(defn wrap-exception-handling
  "Wraps a ring handler with try/catch that will catch all Exceptions, log them,
  and return an HTTP 500 response which includes the Exception type and message,
  if any, in the body."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (log/error e "Exception while handling HTTP request")
        (-> (ring/response (format "Internal Server Error: %s" e))
            (ring/status 500)
            (ring/content-type "text/plain"))))))

(defn wrap-with-puppet-version-header
  "Function that returns a middleware that adds an
  X-Puppet-Version header to the response."
  [handler version]
  (fn [request]
    (let [response (handler request)]
      ; Our compojure app returns nil responses sometimes.
      ; In that case, don't add the header.
      (when response
        (ring/header response "X-Puppet-Version" version)))))