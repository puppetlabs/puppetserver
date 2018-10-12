(ns puppetlabs.services.ca.certificate-authority-core
  (:import [java.io InputStream]
           (clojure.lang IFn)
           (org.joda.time DateTime))
  (:require [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.ring-middleware.core :as middleware]
            [puppetlabs.ring-middleware.utils :as middleware-utils]
            [puppetlabs.puppetserver.liberator-utils :as liberator-utils]
            [puppetlabs.comidi :as comidi :refer [GET ANY PUT DELETE]]
            [bidi.schema :as bidi-schema]
            [slingshot.slingshot :as sling]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [schema.core :as schema]
            [cheshire.core :as cheshire]
            [liberator.core :refer [defresource]]
            ;[liberator.dev :as liberator-dev]
            [liberator.representation :as representation]
            [ring.util.request :as request]
            [ring.util.response :as rr]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.i18n.core :as i18n]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-ca-API-version
  "v1")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'handler' functions for HTTP endpoints

(defn handle-get-certificate
  [subject {:keys [cacert signeddir]}]
  (-> (if-let [certificate (ca/get-certificate subject cacert signeddir)]
        (rr/response certificate)
        (rr/not-found (i18n/tru "Could not find certificate {0}" subject)))
      (rr/content-type "text/plain")))

(defn handle-get-certificate-request
  [subject {:keys [csrdir]}]
  (-> (if-let [certificate-request (ca/get-certificate-request subject csrdir)]
        (rr/response certificate-request)
        (rr/not-found (i18n/tru "Could not find certificate_request {0}" subject)))
      (rr/content-type "text/plain")))

(schema/defn handle-put-certificate-request!
  [subject :- String
   certificate-request :- InputStream
   ca-settings :- ca/CaSettings]
  (sling/try+
    (ca/process-csr-submission! subject certificate-request ca-settings)
    (rr/content-type (rr/response nil) "text/plain")
    (catch ca/csr-validation-failure? {:keys [msg]}
      (log/error msg)
      ;; Respond to all CSR validation failures with a 400
      (middleware-utils/plain-response 400 msg))))

  (schema/defn format-http-date :- (schema/maybe DateTime)
    "Formats an http-date into joda time.  Returns nil for malformed or nil
     http-dates"
    [http-date :- (schema/maybe schema/Str)]
    (when http-date
      (try
        (time-format/parse
          (time-format/formatters :rfc822)
          (string/replace http-date #"GMT" "+0000"))
        (catch IllegalArgumentException e
          nil))))

  (defn handle-get-certificate-revocation-list
    "Always return the crl if no 'If-Modified-Since' header is provided or
    if that header is not in correct http-date format. If the header is
    present and has correct format, only return the crl if the master
    cacrl is newer than the agent crl."
    [request {:keys [cacrl infra-crl-path enable-infra-crl]}]
    (let [agent-crl-last-modified-val (rr/get-header request "If-Modified-Since")
          agent-crl-last-modified-date-time (format-http-date agent-crl-last-modified-val)
          master-crl-to-use (if (true? enable-infra-crl) infra-crl-path cacrl)
          master-crl-last-modified-date-time (ca/get-crl-last-modified master-crl-to-use)]
      (if (or (nil? agent-crl-last-modified-date-time)
              (time/after? master-crl-last-modified-date-time agent-crl-last-modified-date-time))
        (-> (ca/get-certificate-revocation-list master-crl-to-use)
            (rr/response)
            (rr/content-type "text/plain"))
        (-> (rr/response nil)
            (rr/status 304)
            (rr/content-type "text/plain")))))

(schema/defn handle-delete-certificate-request!
  [subject :- String
   ca-settings :- ca/CaSettings]
  (let [response (ca/delete-certificate-request! ca-settings subject)
        outcomes->codes {:success 204 :not-found 404 :error 500}]
    (-> (rr/response (:message response))
        (rr/status ((response :outcome) outcomes->codes))
        (rr/content-type "text/plain"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Web app

(defn try-to-parse
  [body]
  (try
    (cheshire/parse-stream (io/reader body) true)
    (catch Exception e
      (log/debug e))))

(defn malformed
  "Returns a value indicating to liberator that the request is malformed,
  with the given error message assoc'ed into the context."
  [message]
  [true {::malformed message}])

(defn conflict
  "Returns a value indicating to liberator that the request is is conflict
  with the server, with the given error message assoc'ed into the context."
  [message]
  [true {::conflict message}])

(defn get-desired-state
  [context]
  (keyword (get-in context [::json-body :desired_state])))

(defn invalid-state-requested?
  [context]
  (when (= :put (get-in context [:request :request-method]))
    (when-let [desired-state (get-desired-state context)]
      (not (contains? #{:signed :revoked} desired-state)))))

(def media-types
  #{"application/json" "text/pson" "pson"})

(defn content-type-valid?
  [context]
  (let [content-type (request/content-type (:request context))]
    (or
      (nil? content-type)
      (media-types content-type))))

(defn as-json-or-pson
  "This is a stupid hack because of PSON.  We shouldn't have to do this, but
  liberator does not know how to serialize a map as PSON (as it does with JSON),
  so we have to tell it how."
  [x context]
  (let [representation-media-type (get-in context [:representation :media-type])
        accept-header (-> context
                          (get-in [:request :headers])
                          ((partial some #(when (= (string/lower-case (key %))
                                                   "accept")
                                            (val %)))))
        context-with-media-type (cond
                                  (not-empty representation-media-type) context

                                  (not-empty accept-header)
                                  (assoc-in context
                                            [:representation :media-type]
                                            accept-header)

                                  :else
                                  (assoc-in context
                                            [:representation :media-type]
                                            "application/json"))]
    (-> (cheshire/generate-string x)
        (representation/as-response context-with-media-type)
        (assoc :status 200)
        (representation/ring-response))))

(defn as-plain-text-response
  "Create a ring response based on the response info in the supplied context
   and a specific message.  The message is assumed to be plain text and so is
   marked with a 'text/plain; charset=UTF-8' Content-Type header.  This is
   needed for cases where liberator would not mark the Content-Type in the
   response as 'text/plain' on its own, which could otherwise result in the
   underlying webserver dumbly constructing the Content-Type as
   ';charset=UTF-8'.  A Content-Type with a charset and no MIME value would be
   problematic for some clients to interpret."
  [context message]
  (-> message
    (representation/as-response context)
    (assoc :status (:status context))
    (assoc-in [:headers "Content-Type"] "text/plain; charset=UTF-8")
    (representation/ring-response)))

(defresource certificate-status
  [subject settings]
  :allowed-methods [:get :put :delete]

  :available-media-types media-types

  :can-put-to-missing? false

  :conflict?
  (fn [context]
    (let [desired-state (get-desired-state context)]
      (case desired-state
        :revoked
        ;; A signed cert must exist if we are to revoke it.
        (when-not (ca/certificate-exists? settings subject)
          (conflict (i18n/tru "Cannot revoke certificate for host {0} without a signed certificate" subject)))

        :signed
        (or
          ;; A CSR must exist if we are to sign it.
          (when-not (ca/csr-exists? settings subject)
            (conflict (i18n/tru "Cannot sign certificate for host {0} without a certificate request" subject)))

          ;; And the CSR must be valid.
          (when-let [error-message (ca/validate-csr settings subject)]
            (conflict error-message))))))

  :delete!
  (fn [context]
    (ca/delete-certificate! settings subject)
    (ca/delete-certificate-request! settings subject))

  :exists?
  (fn [context]
    (or
      (ca/certificate-exists? settings subject)
      (ca/csr-exists? settings subject)))

  :handle-conflict
  (fn [context]
    (as-plain-text-response context (::conflict context)))

  :handle-exception
  (fn [context]
    (as-plain-text-response context (liberator-utils/exception-handler context)))

  :handle-not-implemented
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      ; We've landed here because :exists? returned false, and we have set
      ; `:can-put-to-missing? false` above.  This happens when
      ; a PUT request comes in with an invalid hostname/subject specified in
      ; in the URL; liberator is pushing us towards a 501 here, but instead
      ; we want to return a 404.  There seems to be some disagreement as to
      ; which makes the most sense in general - see
      ; https://github.com/clojure-liberator/liberator/pull/120
      ; ... but in our case, a 404 definitely makes more sense.
      (-> (assoc context :status 404)
          (as-plain-text-response (i18n/tru "Invalid certificate subject.")))))

  :handle-ok
  (fn [context]
    (-> (ca/get-certificate-status settings subject)
        (as-json-or-pson context)))

  :malformed?
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      (if-let [body (get-in context [:request :body])]
        (if-let [json-body (try-to-parse body)]
          (if-let [desired-state (keyword (:desired_state json-body))]
            (if (schema/check ca/DesiredCertificateState desired-state)
              (malformed
                (i18n/tru "State {0} invalid; Must specify desired state of ''signed'' or ''revoked'' for host {1}."
                  (name desired-state) subject))
              ; this is the happy path. we have a body, it's parsable json,
              ; and the desired_state field is one of (signed revoked)
              [false {::json-body json-body}])
            (malformed (i18n/tru "Missing required parameter \"desired_state\"")))
          (malformed (i18n/tru "Request body is not JSON.")))
        (malformed (i18n/tru "Empty request body.")))))

  :handle-malformed
  (fn [context]
    (if-let [message (::malformed context)]
      message
      (i18n/tru "Bad Request.")))

  :known-content-type?
  (fn [context]
    (if (= :put (get-in context [:request :request-method]))
      (content-type-valid? context)
      true))

  ;; Never return a 201, we're not creating a new cert or anything like that.
  :new? false

  :put!
  (fn [context]
    (let [desired-state (get-desired-state context)]
      (ca/set-certificate-status! settings subject desired-state)
      (-> context
        (assoc-in [:representation :media-type] "text/plain")))))

(defresource certificate-statuses
  [settings]
  :allowed-methods [:get]

  :available-media-types media-types

  :handle-exception
  (fn [context]
    (as-plain-text-response context (liberator-utils/exception-handler context)))

  :handle-ok
  (fn [context]
    (->
      (ca/get-certificate-statuses settings)
      (as-json-or-pson context))))

(schema/defn ^:always-validate web-routes :- bidi-schema/RoutePair
  [ca-settings :- ca/CaSettings]
  (comidi/routes
    (comidi/context ["/v1"]
      (ANY ["/certificate_status/" :subject] [subject]
        (certificate-status subject ca-settings))
      (comidi/context ["/certificate_statuses/"]
        (ANY [[#"[^/]+" :ignored-but-required]] []
          (certificate-statuses ca-settings))
        (ANY [""] [] (middleware-utils/plain-response 400 "Missing URL Segment")))
      (GET ["/certificate/" :subject] [subject]
        (handle-get-certificate subject ca-settings))
      (comidi/context ["/certificate_request/" :subject]
        (GET [""] [subject]
          (handle-get-certificate-request subject ca-settings))
        (PUT [""] [subject :as {body :body}]
          (handle-put-certificate-request! subject body ca-settings)))
        (DELETE [""] [subject]
          (handle-delete-certificate-request! subject ca-settings))
      (GET ["/certificate_revocation_list/" :ignored-node-name] request
        (handle-get-certificate-revocation-list request ca-settings)))
    (comidi/not-found "Not Found")))

(schema/defn ^:always-validate
  wrap-middleware :- IFn
  [handler :- IFn
   puppet-version :- schema/Str]
  (-> handler
    ;(liberator-dev/wrap-trace :header)           ; very useful for debugging!
      (middleware/wrap-uncaught-errors :plain)
      (ringutils/wrap-with-puppet-version-header puppet-version)
      (middleware/wrap-response-logging)))

(schema/defn ^:always-validate
  get-wrapped-handler :- IFn
  [route-handler :- IFn
   ca-settings :- ca/CaSettings
   path :- schema/Str
   authorization-fn :- IFn
   puppet-version :- schema/Str]
  ;; For backward compatibility, requests to the .../certificate_status* endpoint
  ;; will be authorized by a client-whitelist, if one is configured for
  ;; 'certificate_status'.  When we are able to drop support for
  ;; client-whitelist authorization later on, we should be able to get rid of
  ;; the 'wrap-with-trapperkeeper-or-client-whitelist-authorization' function
  ;; and replace with a line chaining the handler into a call to
  ;; 'authorization-fn'.
  (let [whitelist-path (str path
                            (if (not= \/ (last path)) "/")
                            puppet-ca-API-version
                            "/certificate_status")]
    (-> route-handler
        (ringutils/wrap-with-trapperkeeper-or-client-whitelist-authorization
          authorization-fn
          whitelist-path
          (get-in ca-settings [:access-control :certificate-status]))
        i18n/locale-negotiator
        (wrap-middleware puppet-version))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate v1-status :- status-core/StatusCallbackResponse
  [level :- status-core/ServiceStatusDetailLevel]
  {:state :running
   :status {}})
