(ns puppetlabs.services.ca.certificate-authority-core
  (:import  [java.io InputStream])
  (:require [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.puppetserver.liberator-utils :as liberator-utils]
            [slingshot.slingshot :as sling]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [schema.core :as schema]
            [cheshire.core :as cheshire]
            [compojure.core :as compojure :refer [GET ANY PUT]]
            [compojure.route :as route]
            [liberator.core :refer [defresource]]
            [liberator.representation :as representation]
            [liberator.dev :as liberator-dev]
            [ring.util.response :as rr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'handler' functions for HTTP endpoints

(defn handle-get-certificate
  [subject {:keys [cacert signeddir]}]
  (-> (if-let [certificate (ca/get-certificate subject cacert signeddir)]
        (rr/response certificate)
        (rr/not-found (str "Could not find certificate " subject)))
      (rr/content-type "text/plain")))

(defn handle-get-certificate-request
  [subject {:keys [csrdir]}]
  (-> (if-let [certificate-request (ca/get-certificate-request subject csrdir)]
        (rr/response certificate-request)
        (rr/not-found (str "Could not find certificate_request " subject)))
      (rr/content-type "text/plain")))

(schema/defn handle-put-certificate-request!
  [subject :- String
   certificate-request :- InputStream
   ca-settings :- ca/CaSettings]
  (sling/try+
    (ca/process-csr-submission! subject certificate-request ca-settings)
    (rr/content-type (rr/response nil) "text/plain")
    (catch ca/csr-validation-failure? {:keys [message]}
      (log/error message)
      ;; Respond to all CSR validation failures with a 400
      (-> (rr/response message)
          (rr/status 400)
          (rr/content-type "text/plain")))))

(defn handle-get-certificate-revocation-list
  [{:keys [cacrl]}]
  (-> (ca/get-certificate-revocation-list cacrl)
      (rr/response)
      (rr/content-type "text/plain")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app

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
  (let [content-type (get-in context [:request :headers "content-type"])]
    (or
      (nil? content-type)
      (media-types content-type))))

(defn as-json-or-pson
  "This is a stupid hack because of PSON.  We shouldn't have to do this, but
  liberator does not know how to serialize a map as PSON (as it does with JSON),
  so we have to tell it how."
  [x context]
  (let [context-with-media-type (if (string/blank? (get-in context
                                                           [:representation
                                                            :media-type]))
                                  (assoc-in context
                                            [:representation :media-type]
                                            "text/pson")
                                  context)]
    (-> (cheshire/generate-string x)
        (representation/as-response context-with-media-type)
        (assoc :status 200)
        (representation/ring-response))))

(defresource certificate-status
  [subject settings]
  :allowed-methods [:get :put :delete]

  :allowed? (fn [context]
              (ringutils/client-allowed-access?
                (get-in settings [:access-control :certificate-status])
                (:request context)))

  :available-media-types media-types

  :can-put-to-missing? false

  :conflict?
  (fn [context]
    (let [desired-state (get-desired-state context)]
      (case desired-state
        :revoked
        ;; A signed cert must exist if we are to revoke it.
        (when-not (ca/certificate-exists? settings subject)
          (conflict (str "Cannot revoke certificate for host "
                         subject " without a signed certificate")))

        :signed
        (or
          ;; A CSR must exist if we are to sign it.
          (when-not (ca/csr-exists? settings subject)
            (conflict (str "Cannot sign certificate for host "
                           subject " without a certificate request")))

          ;; And the CSR must be valid.
          (when-let [error-message (ca/validate-csr settings subject)]
            (conflict error-message))))))

  :delete!
  (fn [context]
    (ca/delete-certificate! settings subject))

  :exists?
  (fn [context]
    (or
      (ca/certificate-exists? settings subject)
      (ca/csr-exists? settings subject)))

  :handle-conflict
  (fn [context]
    (::conflict context))

  :handle-exception liberator-utils/exception-handler

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
      (-> "Invalid certificate subject."
          (representation/as-response context)
          (assoc :status 404)
          (representation/ring-response))))

  :handle-ok
  (fn [context]
    (-> (ca/get-certificate-status settings subject)
        (as-json-or-pson context)))

  :malformed?
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      (if-let [body (get-in context [:request :body])]
        (if-let [json-body (try-to-parse body)]
          (let [desired-state (keyword (:desired_state json-body))]
            (if (schema/check ca/DesiredCertificateState desired-state)
              (malformed
                (format
                  "State %s invalid; Must specify desired state of 'signed' or 'revoked' for host %s."
                  (name desired-state) subject))
              [false {::json-body json-body}]))
          (malformed "Request body is not JSON."))
        (malformed "Empty request body."))))

  :handle-malformed
  (fn [context]
    (if-let [message (::malformed context)]
      message
      "Bad Request."))

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
      (ca/set-certificate-status! settings subject desired-state))))

(defresource certificate-statuses
  [settings]
  :allowed-methods [:get]

  :allowed? (fn [context]
              (ringutils/client-allowed-access?
                (get-in settings [:access-control :certificate-status])
                (:request context)))

  :available-media-types media-types

  :handle-exception liberator-utils/exception-handler

  :handle-ok
  (fn [context]
    (->
      (ca/get-certificate-statuses settings)
      (as-json-or-pson context))))

(schema/defn v1-routes
  [ca-settings :- ca/CaSettings]
  (compojure/routes
    (ANY "/certificate_status/:subject" [subject]
         (certificate-status subject ca-settings))
    (ANY "/certificate_statuses/:ignored-but-required" [do-not-use]
         (certificate-statuses ca-settings))
    (GET "/certificate/:subject" [subject]
         (handle-get-certificate subject ca-settings))
    (compojure/context "/certificate_request/:subject" [subject]
                       (GET "/" []
                            (handle-get-certificate-request subject ca-settings))
                       (PUT "/" {body :body}
                            (handle-put-certificate-request! subject body ca-settings)))
    (GET "/certificate_revocation_list/:ignored-node-name" []
         (handle-get-certificate-revocation-list ca-settings))))

(defn routes
  [ca-settings]
  (compojure/routes
    (compojure/context "/v1" [] (v1-routes ca-settings))
    (route/not-found "Not Found")))

(schema/defn ^:always-validate
  build-ring-handler
  [ca-settings :- ca/CaSettings
   puppet-version :- schema/Str]
  (-> (routes ca-settings)
      ;(liberator-dev/wrap-trace :header)           ; very useful for debugging!
      (ringutils/wrap-exception-handling)
      (ringutils/wrap-with-puppet-version-header puppet-version)
      (ringutils/wrap-response-logging)))
