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

; TODO - need to test this against the console + `puppet certificate`.  Issues w/ headers? (Accept / Content-Type, JSON vs. PSON?)
(liberator/defresource certificate-status
  [certname]
  :allowed-methods [:get :put :delete]

  :available-media-types ["application/json"]

  :delete! (fn [context]
             (ca/delete-certificate! certname))

  :exists? (fn [context]
             (ca/certificate-exists? certname))

  :handle-exception utils/exception-handler

  :handle-ok (fn [context]
               (ca/get-certificate-status certname))

  :malformed? (fn [context]
                (when (= :put (get-in context [:request :request-method]))
                  (let [state (get-desired-state context)]
                    (schema/check ca/CertState state))))

  ; Never return a 201, we're not creating a new cert or anything like that.
  :new? false

  :put! (fn [context]
          (let [desired-state (get-desired-state context)]
            (ca/set-certificate-status! certname desired-state)))

  ; Requests must be JSON for us to handle them.
  :valid-content-header? (fn [context]
                           (ringutils/json-request? (get context :request))))

(liberator/defresource certificate-statuses
  :allowed-methods [:get]

  :available-media-types ["application/json"]

  :handle-exception utils/exception-handler

  :handle-ok (fn [context]
               (ca/get-certificate-statuses)))

(schema/defn routes
  [ca-settings :- ca/CaSettings]
  (compojure/context "/:environment" [environment]
    (compojure/routes
      (ANY "/certificate_status/:certname" [certname]
        (certificate-status certname))
      (ANY "/certificate_statuses/:ignored-but-required" [do-not-use]
         certificate-statuses)
      (GET "/certificate/:subject" [subject]
        (handle-get-certificate subject ca-settings))
      (compojure/context "/certificate_request/:subject" [subject]
        (GET "/" []
          (handle-get-certificate-request subject ca-settings))
        (PUT "/" {body :body}
          (handle-put-certificate-request! subject body ca-settings)))
      (GET "/certificate_revocation_list/:ignored-node-name" []
        (handle-get-certificate-revocation-list ca-settings)))))

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

(schema/defn ^:always-validate
  compojure-app
  [ca-settings :- ca/CaSettings
   puppet-version :- String]
  (-> (routes ca-settings)
      (json/wrap-json-body {:keywords? true})
      (wrap-with-puppet-version-header puppet-version)
      (ringutils/wrap-response-logging)))
