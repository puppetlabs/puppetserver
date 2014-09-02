(ns puppetlabs.services.ca.certificate-authority-core
  (:import  [java.io InputStream])
  (:require [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.puppetserver.liberator-utils :as utils]
            [slingshot.slingshot :as sling]
            [clojure.tools.logging :as log]
            [schema.core :as schema]
            [compojure.core :as compojure :refer [GET ANY PUT]]
            [liberator.core :as liberator]
            [liberator.representation :as representation]
            [ring.middleware.json :as json]
            [ring.util.response :as rr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn bad-request?
  "Given a map (thrown via slingshot), should it result in a
  HTTP 400 (bad request) response being sent back to the client?"
  [x]
  (when (map? x)
    (let [type (:type x)
          expected-types #{:disallowed-extension
                           :duplicate-cert
                           :hostname-mismatch
                           :invalid-signature
                           :invalid-subject-name}]
      (contains? expected-types type))))

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
    (catch bad-request? {:keys [message]}
      (log/error message)
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

(defn get-desired-state
  [context]
  (keyword (get-in context [:request :body :desired_state])))

(liberator/defresource certificate-status
  [subject settings]
  :allowed-methods [:get :put :delete]

  :available-media-types ["application/json"]

  :can-put-to-missing? false

  :delete!
  (fn [context]
    (ca/delete-certificate! settings subject))

  :exists?
  (fn [context]
    (ca/certificate-exists? settings subject))

  :handle-exception utils/exception-handler

  :handle-not-implemented
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      ; We've landed here because :exists? returned false, and we have set
      ; `:can-put-to-missing? false` above.  This happens when
      ; a request comes in with an invalid hostname/subject specified in
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
    (ca/get-certificate-status settings subject))

  :malformed?
  (fn [context]
    (when (= :put (get-in context [:request :request-method]))
      (let [state (get-desired-state context)]
        (schema/check ca/DesiredCertificateState state))))

  :handle-malformed
  (fn [context]
    (if (ringutils/json-request? (get context :request))
      "Bad Request."
      "Request headers must include 'Content-Type: application/json'."))

  ;; Never return a 201, we're not creating a new cert or anything like that.
  :new? false

  :put!
  (fn [context]
    (let [desired-state (get-desired-state context)]
      (ca/set-certificate-status! settings subject desired-state))))

(liberator/defresource certificate-statuses
  [settings]
  :allowed-methods [:get]

  :available-media-types ["application/json"]

  :handle-exception utils/exception-handler

  :handle-ok
  (fn [context]
    (ca/get-certificate-statuses settings)))

(schema/defn routes
  [ca-settings :- ca/CaSettings]
  (compojure/context "/:environment" [environment]
    (compojure/routes
      (ANY "/certificate_status/:subject" [subject]
        (certificate-status subject ca-settings))
      (ANY "/certificate_statuses/:ignored-but-required" [do-not-use]
        (certificate-statuses ca-settings)))
      (GET "/certificate/:subject" [subject]
        (handle-get-certificate subject ca-settings))
      (compojure/context "/certificate_request/:subject" [subject]
        (GET "/" []
          (handle-get-certificate-request subject ca-settings))
        (PUT "/" {body :body}
          (handle-put-certificate-request! subject body ca-settings)))
      (GET "/certificate_revocation_list/:ignored-node-name" []
        (handle-get-certificate-revocation-list ca-settings))))

(defn wrap-with-puppet-version-header
  "Function that returns a middleware that adds an
  X-Puppet-Version header to the response."
  [handler version]
  (fn [request]
    (let [response (handler request)]
      ; Our compojure app returns nil responses sometimes.
      ; In that case, don't add the header.
      (when response
        (rr/header response "X-Puppet-Version" version)))))

(defn treat-pson-as-json
  "For requests with a Content-type of 'text/pson', replaces the Content-type
   with 'application/json'.

   This is necessary because some of the clients of this ring application,
   namely the PE Console, still send 'Content-Type: text/pson'.  Once this
   is no longer true, this function can and should be deleted."
  [handler]
  (fn [request]
    (let [content-type (:content-type request)
          request (if (and content-type (= "text/pson" (.trim content-type)))
                    (assoc request :content-type "application/json")
                    request)]
      (handler request))))

(schema/defn ^:always-validate
  compojure-app
  [ca-settings :- ca/CaSettings
   puppet-version :- schema/Str]
  (-> (routes ca-settings)
      (json/wrap-json-body {:keywords? true})
      (treat-pson-as-json)
      (wrap-with-puppet-version-header puppet-version)
      (ringutils/wrap-response-logging)))
