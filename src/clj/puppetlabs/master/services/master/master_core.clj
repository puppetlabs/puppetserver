(ns puppetlabs.master.services.master.master-core
  (:require [compojure.core :as compojure]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [puppetlabs.master.ringutils :as ringutils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v2_0-routes
  "Creates the compojure routes to handle the master's '/v2.0' routes."
  [request-handler]
  (compojure/routes
    (compojure/GET "/environments" request
                   (request-handler request))))

(defn legacy-routes
  "Creates the compojure routes to handle the master's 'legacy' routes
   - ie, any route without a version in its path (eg, /v2.0/whatever) - but
   excluding the CA-related endpoints, which are handled separately by the
   CA service."
  [request-handler environment]
  (compojure/routes
    ; TODO there are a bunch more that we'll need to add here
    ; https://tickets.puppetlabs.com/browse/PE-3977
    (compojure/GET "/node/*" request
                   (request-handler environment request))
    (compojure/GET "/facts/*" request
                   (request-handler environment request))
    (compojure/GET "/file_content/*" request
                   (request-handler environment request))
    (compojure/GET "/file_metadatas/*" request
                   (request-handler environment request))
    (compojure/GET "/file_metadata/*" request
                   (request-handler environment request))
    (compojure/GET "/catalog/*" request
                   (request-handler environment request))
    (compojure/POST "/catalog/*" request
                    (request-handler environment request))
    (compojure/PUT "/report/*" request
                   (request-handler environment request))
    (compojure/GET "/resource_type/*" request
                   (request-handler environment request))))

(defn root-routes
  "Creates all of the compojure routes for the master."
  [request-handler]
  (compojure/routes
    (compojure/context "/v2.0" request
                       (v2_0-routes request-handler))
    (compojure/context "/:environment" [environment]
                       (legacy-routes request-handler environment))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn compojure-app
  "Creates the entire compojure application (all routes and middleware)."
  [request-handler]
  {:pre [(fn? request-handler)]}
  (-> (root-routes request-handler)
      handler/api
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging))
