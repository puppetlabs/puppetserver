(ns puppetlabs.master.services.ca.certificate-authority-core
  (:require [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.master.ringutils :as ringutils]
            [schema.core :as schema]
            [compojure.core :as compojure]
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
  [subject
   certificate-request
   {:keys [autosign csrdir load-path] :as ca-settings} :- ca/CaSettings]
  (if (ca/autosign-csr? autosign subject certificate-request load-path)
    (ca/autosign-certificate-request! subject certificate-request ca-settings)
    (ca/save-certificate-request! subject certificate-request csrdir))
  (rr/content-type (rr/response nil) "text/plain"))

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

(schema/defn ^:always-validate
  compojure-app
  [ca-settings :- ca/CaSettings]
  (-> (routes ca-settings)
      (ringutils/wrap-response-logging)))
