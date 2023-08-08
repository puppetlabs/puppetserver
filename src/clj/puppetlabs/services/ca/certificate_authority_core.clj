(ns puppetlabs.services.ca.certificate-authority-core
  (:require [bidi.schema :as bidi-schema]
            [cheshire.core :as cheshire]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [liberator.core :refer [defresource]]
            [liberator.representation :as representation]
            [puppetlabs.comidi :as comidi :refer [ANY DELETE GET POST PUT]]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.common :as common]
            [puppetlabs.puppetserver.liberator-utils :as liberator-utils]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.ring-middleware.core :as middleware]
            [puppetlabs.ring-middleware.utils :as middleware-utils]
            [puppetlabs.ssl-utils.core :as utils]
            [puppetlabs.trapperkeeper.authorization.ring-middleware :as auth-middleware]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [ring.util.request :as request]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [slingshot.slingshot :as sling])
  (:import (clojure.lang IFn)
           (java.io ByteArrayInputStream InputStream StringWriter)
           (org.joda.time DateTime)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-ca-API-version
  "v1")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'handler' functions for HTTP endpoints


(schema/defn format-http-date :- (schema/maybe DateTime)
  "Formats an http-date into joda time.  Returns nil for malformed or nil
   http-dates"
  [http-date :- (schema/maybe schema/Str)]
  (when http-date
    (try
      (time-format/parse
        (time-format/formatters :rfc822)
        (string/replace http-date #"GMT" "+0000"))
      (catch IllegalArgumentException _
        nil))))

(defn handle-get-certificate
  [subject {:keys [cacert signeddir]} request]
  (-> (if-let [certificate-path (ca/get-certificate-path subject cacert signeddir)]
        (let [last-modified-val (rr/get-header request "If-Modified-Since")
              last-modified-date-time (format-http-date last-modified-val)
              cert-last-modified-date-time (ca/get-file-last-modified certificate-path)]
          (if (or (nil? last-modified-date-time)
                  (time/after? cert-last-modified-date-time last-modified-date-time))
            (rr/response (slurp certificate-path))
            (-> (rr/response nil)
                (rr/status 304))))
        (rr/not-found (i18n/tru "Could not find certificate {0}" subject)))
      (rr/content-type "text/plain")))

(defn handle-get-certificate-request
  [subject {:keys [csrdir]}]
  (-> (if-let [certificate-request (ca/get-certificate-request subject csrdir)]
        (rr/response certificate-request)
        (rr/not-found (i18n/tru "Could not find certificate_request {0}" subject)))
      (rr/content-type "text/plain")))


(schema/defn handle-put-certificate-request!
  [ca-settings :- ca/CaSettings
   report-activity
   {:keys [body] {:keys [subject]} :route-params :as request}]
  (sling/try+
    (let [report-activity-fn (ca/create-report-activity-fn report-activity request)]
      (ca/process-csr-submission! subject body ca-settings report-activity-fn)
      (rr/content-type (rr/response nil) "text/plain"))
    (catch ca/csr-validation-failure? {:keys [msg]}
      (log/error msg)
      ;; Respond to all CSR validation failures with a 400
      (middleware-utils/plain-response 400 msg))))


(schema/defn resolve-crl-information
  "Create a map that has the appropriate path, lock, timeout and descriptor for the crl being used"
  [{:keys [enable-infra-crl cacrl infra-crl-path crl-lock crl-lock-timeout-seconds]} :- ca/CaSettings]
  {:path (if (true? enable-infra-crl) infra-crl-path cacrl)
   :lock crl-lock
   :descriptor ca/crl-lock-descriptor
   :timeout crl-lock-timeout-seconds})

(defn handle-get-certificate-revocation-list
  "Always return the crl if no 'If-Modified-Since' header is provided or
  if that header is not in correct http-date format. If the header is
  present and has correct format, only return the crl if the server
  cacrl is newer than the agent crl."
  [request ca-settings]
  (let [agent-crl-last-modified-val (rr/get-header request "If-Modified-Since")
        agent-crl-last-modified-date-time (format-http-date agent-crl-last-modified-val)
        {:keys [path lock descriptor timeout]} (resolve-crl-information ca-settings)]
    ;; Since the locks are reentrant, obtain the read lock to prevent modification during the
    ;; window of time between when the last-modified is read and when the crl content is potentially read.
    (common/with-safe-read-lock lock descriptor timeout
        (if (or (nil? agent-crl-last-modified-date-time)
                (time/after? (ca/get-file-last-modified path lock descriptor timeout)
                             agent-crl-last-modified-date-time))
          (-> (ca/get-certificate-revocation-list path lock descriptor timeout)
              (rr/response)
              (rr/content-type "text/plain"))
          (-> (rr/response nil)
              (rr/status 304)
              (rr/content-type "text/plain"))))))

(schema/defn handle-put-certificate-revocation-list!
  [incoming-crl-pem :- InputStream
   {:keys [cacrl cacert] :as ca-settings} :- ca/CaSettings]
  (try
    (let [byte-stream (-> incoming-crl-pem
                          ca/input-stream->byte-array
                          ByteArrayInputStream.)
          incoming-crls (utils/pem->crls byte-stream)]
      (if (empty? incoming-crls)
        (do
          (log/info (i18n/trs "No valid CRLs submitted, nothing will be updated."))
          (middleware-utils/plain-response 400 "No valid CRLs submitted."))
        (do
          (ca/update-crls! incoming-crls cacrl cacert ca-settings)
          (middleware-utils/plain-response 200 "Successfully updated CRLs."))))
    (catch IllegalArgumentException e
      (let [error-msg (.getMessage e)]
        (log/error error-msg)
        (middleware-utils/plain-response 400 error-msg)))))

(schema/defn handle-delete-certificate-request!
  [subject :- String
   ca-settings :- ca/CaSettings]
  (let [response (ca/delete-certificate-request! ca-settings subject)
        outcomes->codes {:success 204 :not-found 404 :error 500}]
    (if (not= (response :outcome) :success) 
      (-> (rr/response (:message response)) 
          (rr/status ((response :outcome) outcomes->codes))
          (rr/content-type "text/plain"))
      (-> (rr/response (:message response))
          (rr/status ((response :outcome) outcomes->codes))))))

(schema/defn handle-get-ca-expirations
  [ca-settings :- ca/CaSettings]
  (let [response {:ca-certs (ca/ca-expiration-dates (:cacert ca-settings))
                  :crls (ca/crl-expiration-dates (:cacrl ca-settings))}]
    (-> (rr/response (cheshire/generate-string response))
        (rr/status 200)
        (rr/content-type "application/json"))))

(defn try-to-parse
  [body]
  (try
    (cheshire/parse-stream (io/reader body) true)
    (catch Exception e
      (log/debug e))))

(schema/defn handle-cert-clean
  [request
   ca-settings :- ca/CaSettings
   report-activity]
  (if-let [json-body (try-to-parse (:body request))]
    ;; TODO support async mode
    (if (true? (:async json-body))
      (-> (rr/response "Async mode is not currently supported.")
          (rr/status 400)
          (rr/content-type "text/plain"))
      (if-let [certnames (:certnames json-body)]
        (let [{existing-certs true
               missing-certs false} (group-by
                                     #(ca/certificate-exists? ca-settings %)
                                     certnames)
              message (when (seq missing-certs)
                        (format "The following certs do not exist and cannot be revoked: %s"
                                (vec missing-certs)))
              report-activity-fn (ca/create-report-activity-fn report-activity request)]
          (try
            (ca/revoke-existing-certs! ca-settings existing-certs report-activity-fn)
            (ca/delete-certificates! ca-settings existing-certs)
            (-> (rr/response (or message "Successfully cleaned all certs."))
                (rr/status 200)
                (rr/content-type "text/plain"))
            (catch Exception e
              (-> (rr/response (str "Error while cleaning certs: " (.getMessage e)))
                  (rr/status 500)
                  (rr/content-type "text/plain")))))
        (-> (rr/response "Missing required key: 'certnames'. Please supply the list of certs you want to clean.")
            (rr/status 400)
            (rr/content-type "text/plain"))))
    (-> (rr/response "Request body is not JSON.")
        (rr/status 400)
        (rr/content-type "text/plain"))))

(schema/defn ^:always-validate
  handle-cert-renewal
  "Given a request and the CA settings, if there is a cert present in the request
  (either in the ssl-client-cert property of the request, or as an x-client-cert
  field in the header when allow-header-cert-info is set to true) and the cert in
  the request is valid and signed by the this CA. then generate a renewed cert and
  return it in the response body"
  [request
   {:keys [cacert cakey allow-auto-renewal allow-header-cert-info] :as ca-settings} :- ca/CaSettings
   report-activity]
  (if allow-auto-renewal
    (let [request-cert (auth-middleware/request->cert request allow-header-cert-info)]
      (if request-cert
        (let [signing-cert (utils/pem->ca-cert cacert cakey)]
          (if (ca/cert-authority-id-match-ca-subject-id? request-cert signing-cert)
            (do
              (log/info (i18n/trs "Certificate present, processing renewal request"))
              (let [cert-signing-result (ca/renew-certificate! request-cert ca-settings report-activity)
                    cert-writer (StringWriter.)]
                ;; has side effect of writing to the writer
                (utils/cert->pem! cert-signing-result cert-writer)
                (-> (rr/response (.toString cert-writer))
                    (rr/content-type "text/plain"))))
            (do
              (log/info (i18n/trs "Certificate present, but does not match signature"))
              (-> (rr/response (i18n/tru "Certificate present, but does not match signature"))
                  (rr/status 403)
                  (rr/content-type "text/plain")))))
        (do
          (log/info (i18n/trs "No certificate found in renewal request"))
          (-> (rr/bad-request (i18n/tru "No certificate found in renewal request"))
              (rr/content-type "text/plain")))))
    (-> (rr/response "Not Found")
        (rr/status 404)
        (rr/content-type "text/plain"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Web app

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

(defn merge-request-settings
  [settings context]
  (if-let [cert-ttl (get-in context [::json-body :cert_ttl])]
    (assoc settings :ca-ttl cert-ttl)
    settings))

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
  [subject settings report-activity]
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
  (fn [_context]
    (ca/delete-certificate! settings subject)
    (ca/delete-certificate-request! settings subject))

  :exists?
  (fn [_context]
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
    (-> (ca/get-cert-or-csr-status settings subject)
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
              (let [cert-ttl (:cert_ttl json-body)]
                (if (and cert-ttl (schema/check schema/Int cert-ttl))
                  (malformed
                   (i18n/tru "cert_ttl specified for host {0} must be an integer, not \"{1}\"" subject cert-ttl))
                  ; this is the happy path. we have a body, it's parsable json,
                  ; and the desired_state field is one of (signed revoked), and
                  ; it potentially has a valid cert_ttl field
                  [false {::json-body json-body}])))
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
     (let [desired-state (get-desired-state context)
           request (:request context)
           report-activity-fn (ca/create-report-activity-fn report-activity request)]
       (ca/set-certificate-status!
         (merge-request-settings settings context)
         subject
         desired-state
         report-activity-fn)
       (-> context
         (assoc-in [:representation :media-type] "text/plain")))))

(defresource certificate-statuses
  [request settings]
  :allowed-methods [:get]

  :available-media-types media-types

  :handle-exception
  (fn [context]
    (as-plain-text-response context (liberator-utils/exception-handler context)))

  :handle-ok
  (fn [context]
    (let [queried-state (get-in request [:params "state"])]
      (->
        (if (some #(= queried-state %) ["requested" "signed" "revoked"])
          (ca/filter-by-certificate-state settings queried-state)
          (ca/get-cert-and-csr-statuses settings))
        (as-json-or-pson context)))))

(schema/defn ^:always-validate web-routes :- bidi-schema/RoutePair
  [ca-settings :- ca/CaSettings
   report-activity]
  (comidi/routes
    (comidi/context ["/v1"]
      (ANY ["/certificate_status/" :subject] [subject]
          (certificate-status subject ca-settings report-activity))
      (comidi/context ["/certificate_statuses/"]
        (ANY [[#"[^/]+" :ignored-but-required]] request
          (certificate-statuses request ca-settings))
        (ANY [""] [] (middleware-utils/plain-response 400 "Missing URL Segment")))
      (GET ["/certificate/" :subject] request
        (handle-get-certificate (get-in request [:params :subject]) ca-settings request))
      (comidi/context ["/certificate_request/" :subject]
        (GET [""] [subject]
          (handle-get-certificate-request subject ca-settings))
        (PUT [""] request
          (handle-put-certificate-request! ca-settings report-activity request))
        (DELETE [""] [subject]
          (handle-delete-certificate-request! subject ca-settings)))
      (GET ["/certificate_revocation_list/" :ignored-node-name] request
        (handle-get-certificate-revocation-list request ca-settings))
      (PUT ["/certificate_revocation_list"] request
        (handle-put-certificate-revocation-list! (:body request) ca-settings))
      (GET ["/expirations"] _request
        (handle-get-ca-expirations ca-settings))
      (PUT ["/clean"] request
        (handle-cert-clean request ca-settings report-activity))
      (POST ["/certificate_renewal"] request
        (handle-cert-renewal request ca-settings report-activity)))
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
                            (when (not= \/ (last path)) "/")
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
  [_level :- status-core/ServiceStatusDetailLevel]
  {:state :running
   :status {}})
