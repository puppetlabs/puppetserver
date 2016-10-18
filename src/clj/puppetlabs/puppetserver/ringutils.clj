(ns puppetlabs.puppetserver.ringutils
  (:import (clojure.lang IFn)
           (java.security.cert X509Certificate))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [ring.util.response :as ring]
            [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def WhitelistSettings
  {(schema/optional-key :authorization-required) schema/Bool
   (schema/optional-key :client-whitelist)       [schema/Str]})

(def RingRequest
  {:uri schema/Str
   (schema/optional-key :ssl-client-cert) (schema/maybe X509Certificate)
   schema/Keyword schema/Any})

(def RingResponse
  {:status schema/Int
   :headers {schema/Str schema/Any}
   :body schema/Any
   schema/Keyword schema/Any})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn log-access-denied
  [uri certificate]
  "Log a message to info stating that the client is not in the
   access control whitelist."
  (let [subject (ssl-utils/get-cn-from-x509-certificate certificate)]
    (log/info
     (format "%s\n%s" (i18n/trs "Client ''{0}'' access to {1} rejected;" subject uri) (i18n/trs "client not found in whitelist configuration.")))))

(defn client-on-whitelist?
  "Test if the certificate subject is on the client whitelist."
  [settings certificate]
  (let [whitelist (-> settings
                      :client-whitelist
                      (set))
        client    (ssl-utils/get-cn-from-x509-certificate certificate)]
    (contains? whitelist client)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

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
        (log/info (i18n/trs "Access to {0} rejected; no client certificate found" (:uri req)))
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
      {:status 403 :body "Forbidden."})))

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

;; This function exists to support backward-compatible usage of a
;; client-whitelist to protect access to some Clojure endpoints.  When support
;; for client-whitelist authorization is dropped, this function should deleted.
;; Callers would presumably be using a trapperkeeper-authorization handler
;; for all endpoint authorization.
(schema/defn ^:always-validate
  wrap-with-trapperkeeper-or-client-whitelist-authorization
  "Middleware function that routes a request through either an authorization
  handler derived from the supplied 'authorization-fn' or to a client-whitelist
  handler.

  The 'authorization-fn' is expected to return a handler when called
  and this function and accept a single argument, an downstream handler that the
  authorization-fn should route a handled request to.  The authorization-fn
  is called with 'base-handler' as its parameter.

  Requests are only routed to the client-whitelist handler if the request 'uri'
  starts with the value provided to this function for 'whitelist-path' and if
  the 'whitelist-settings' are non-empty.  In all other cases, requests are
  routed to the handler constructed from the 'authorization-fn'"
  [base-handler :- IFn
   authorization-fn :- IFn
   whitelist-path :- schema/Str
   whitelist-settings :- (schema/maybe WhitelistSettings)]
  (let [handler-with-trapperkeeper-authorization (authorization-fn base-handler)]
    (if-let [handler-with-client-whitelist-authorization
             (if (or (false? (:authorization-required whitelist-settings))
                     (not-empty (:client-whitelist whitelist-settings)))
               (wrap-with-cert-whitelist-check base-handler whitelist-settings))]
      (fn [request]
        (if (and (.startsWith (:uri request) whitelist-path)
                 whitelist-settings)
          (handler-with-client-whitelist-authorization request)
          (handler-with-trapperkeeper-authorization request)))
      (fn [request]
        (handler-with-trapperkeeper-authorization request)))))
