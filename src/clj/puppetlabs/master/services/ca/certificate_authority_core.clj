(ns puppetlabs.master.services.ca.certificate-authority-core
  (:require [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.master.ringutils :as ringutils]
            [compojure.core :as compojure]
            [compojure.handler :as handler]
            [ring.util.response :as rr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'handler' functions for HTTP endpoints

(defn handle-get-certificate
  [subject {:keys [cacert certdir]}]
  (-> (if-let [certificate (ca/get-certificate subject cacert certdir)]
        (rr/response certificate)
        (rr/not-found (str "Could not find certificate " subject)))
      (rr/content-type "text/plain")))

(defn handle-get-certificate-request
  [subject {:keys [csrdir]}]
  (-> (if-let [certificate-request (ca/get-certificate-request subject csrdir)]
        (rr/response certificate-request)
        (rr/not-found (str "Could not find certificate_request " subject)))
      (rr/content-type "text/plain")))

(defn handle-put-certificate-request!
  [subject certificate-request ca-settings]
  (let [expiration-date (ca/autosign-certificate-request!
                          subject certificate-request ca-settings)]
    ;; TODO return something proper (PE-3178)
    (-> (str "---\n"
             "  - !ruby/object:Puppet::SSL::CertificateRequest\n"
             "name: " subject "\n"
             "content: !ruby/object:OpenSSL::X509::Request {}\n"
             "expiration: " expiration-date)
        (rr/response)
        (rr/content-type "text/yaml"))))

(defn handle-get-certificate-revocation-list
  [{:keys [cacrl]}]
  (-> (ca/get-certificate-revocation-list cacrl)
      (rr/response)
      (rr/content-type "text/plain")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app

(defn routes
  [ca-settings]
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

(defn compojure-app
  [ca-settings]
  (->
    (routes ca-settings)
    (handler/api)
    (ringutils/wrap-response-logging)))
