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
            [me.raynes.fs :as fs]
            [cheshire.core :as json]))

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
    (do
      (log/debugf "AUTHZ: Loading rules from %s" rules-path)
      (config/config-file->rules rules-path))
    (do
      (log/debugf "AUTHZ: Loading rules from compiled-in defaults")
      authz-default-rules)))

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

(defn request->log-data
  "Return a map suitable for logging given a Ring request map"
  [request]
  (let [interesting-keys [:request-method :uri :remote-addr :query-string]
        cn (if-let [name (request->name request)] name "UNKNOWN")]
    (merge (select-keys request interesting-keys) {:client-cert-cn cn})))

(schema/defn ^:always-validate authz-request-data-str :- schema/Str
  "Format a ring request suitable for use in log messages."
  [authorization-result :- rules/AuthorizationResult
   request :- RingRequest]
  (let [small-request (request->log-data request)
        authz-data (merge authorization-result small-request)]
    (json/generate-string authz-data)))

(schema/defn ^:always-validate authorized? :- schema/Bool
  "Given an AuthorizationResult, return true if authorized or false if not."
  [authorization-result :- rules/AuthorizationResult]
  (if (:authorized authorization-result) true false))

(schema/defn ^:always-validate authorize-request :- rules/AuthorizationResult
  "Check if a request is authorized with debug logging."
  [rules :- rules/Rules
   request :- RingRequest]
  (if-let [name (request->name request)]
    (let [authorization-result (rules/allowed? rules request name)
          allowed (:authorized authorization-result)
          log-data (authz-request-data-str authorization-result request)
          access-str (if allowed "Granted" "Denied")]
      (log/debugf "AUTHZ: Access %s JSON=%s" access-str log-data)
      authorization-result)
    (do                                                     ; FIXME Do we really need this "then" branch?
      (log/debugf "AUTHZ: Access Denied (No ssl-client-cert present) JSON=%s"
                  (authz-request-data-str {} request))
      ; This seems terrible, look into if tk-authorization has a way to generate an AuthorizationResult
      {:authorized false, :message "No ssl-client cert present"})))

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
      (let [rules (rules-fn)
            authorization-result (authorize-request rules req)
            {:keys [message]} authorization-result]
        (if (authorized? authorization-result)
          (handler req)
          {:status 403 :body message})))))

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
