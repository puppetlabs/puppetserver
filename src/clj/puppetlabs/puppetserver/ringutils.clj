(ns puppetlabs.puppetserver.ringutils
  (:import (clojure.lang IFn)
           (java.security.cert X509Certificate))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.trapperkeeper.authorization.file.config :as config]
            [puppetlabs.trapperkeeper.authorization.rules :as rules]
            [ring.util.response :as ring]
            [schema.core :as schema]
            [me.raynes.fs :as fs]))

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

; First try to load rules from /etc/puppetlabs/puppetserver/rules.conf
; if it doesn't exist, load from code.

(def rules-path "/etc/puppetlabs/puppetserver/rules.conf")

(def authz-default-rules
  "The default set of Rules from code."
  (-> rules/empty-rules
      (rules/add-rule (rules/new-path-rule "/puppet-admin-api/"))
      (rules/add-rule (rules/new-path-rule "/certificate_status/"))
      (rules/add-rule (rules/new-path-rule "/certificate_statuses/"))))

(defn authz-rules
  "Load a authz rules from rules-path if it exists, otherwise load hard-coded
  defaults from code.  This is a function to allow modification of the rules
  file at runtime."
  []
  (if (fs/exists? rules-path)
    (config/config-file->rules rules-path)
    authz-default-rules))

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

(schema/defn ^:always-validate request->name :- (schema/maybe schema/Str)
  "Return the name of the client by parsing the subject of the client cert from
  the request.  Return nil if the client cert is absent or cannot be parsed."
  [request :- RingRequest]
  (let [{:keys [ssl-client-cert]} request]
    (if ssl-client-cert (get-cert-subject ssl-client-cert))))

(schema/defn ^:always-validate authorized? :- schema/Bool
  "(SERVER-768) replacement for client-allowed-access?"
  [rules :- rules/Rules
   request :- RingRequest]
  (if-let [name (request->name request)]
    (let [request-for-tk-authz-schema (merge {:remote-addr ""} request)
          {:keys [authorized message]} (rules/allowed? rules request-for-tk-authz-schema name)]
      (log/debugf "Authorized: %s for client %s message: %s request: %s"
                  authorized name message request-for-tk-authz-schema)
      authorized)
    (do
      (log/debugf "Authorized: false, no ssl-client-cert for request %s"
                  request)
      false)))

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

(schema/defn ^:always-validate wrap-with-authz-rules-check :- IFn
  "A ring middleware that checks to make sure the client is authorized to
  make the request using trapperkeeper-authorization Rules."
  ([handler :- IFn]
    (wrap-with-authz-rules-check handler authz-rules))
  ([handler :- IFn rules-fn :- IFn]
    (fn [req]
      (let [rules (rules-fn)]
        (if (authorized? rules req)                         ; TODO: grab the message describing why for the response body
          (handler req)
          {:status 403 :body "Forbidden (determined by rules.conf)"})))))

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
