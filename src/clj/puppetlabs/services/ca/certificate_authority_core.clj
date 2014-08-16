(ns puppetlabs.services.ca.certificate-authority-core
  (:import  [java.io InputStream])
  (:require [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [slingshot.slingshot :as sling]
            [clojure.tools.logging :as log]
            [schema.core :as schema]
            [compojure.core :as compojure]
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

(schema/defn routes
  [ca-settings :- ca/CaSettings]
  (compojure/context "/:environment" [environment]
    (compojure/routes
      (compojure/GET "/certificate/:subject" [subject]
        (handle-get-certificate subject ca-settings))
      (compojure/context "/certificate_request/:subject" [subject]
        (compojure/GET "/" []
          (handle-get-certificate-request subject ca-settings))
        (compojure/PUT "/" {body :body}
          (handle-put-certificate-request! subject body ca-settings)))
      (compojure/GET "/certificate_revocation_list/:ignored-node-name" []
        (handle-get-certificate-revocation-list ca-settings)))))

(defn wrap-with-puppet-version-header
  "Middleware that adds a X-Puppet-Version header to the response."
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
      (wrap-with-puppet-version-header puppet-version)
      (ringutils/wrap-response-logging)))
