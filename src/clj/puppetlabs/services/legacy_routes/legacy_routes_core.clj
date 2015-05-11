(ns puppetlabs.services.legacy-routes.legacy-routes-core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [ring.util.codec :as ring-codec]
            [compojure.route :as route]
            [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; v3 => v4 header translation support functions

; TODO: Improve the naive parsing here with a real library like
; https://github.com/ToBeReplaced/http-accept-headers
(defn- map-accept-raw-to-binary
  "Convert Accept: raw media types to Accept: binary.  NOTE: This method does
  not conform to RFC-2616 Accept: header specification, but then again
  neither does Accept: raw or Accept: binary

  The split on `,` is naive, the method should take into account `;` parameters
  and such to be RFC compliant, but puppet agent does not use this format so
  implementing the full spec is unnecessary."
  [request]
  (if-let [accept (get-in request [:headers "accept"])]
    (let [vals (str/split accept #"\s*,\s*")]               ; BAD! See RFC-2616
      (assoc-in request [:headers "accept"]
        (str/join ", " (distinct (replace {"raw" "binary"} vals)))))
    request))

(defn- map-accept-s-pson-to-binary
  "Convert Accept: s, pson media types to Accept: binary given a ring request
  map.  This method should only be used to munge file_bucket_file API requests
  from legacy agents speaking to a contemporary master."
  [request]
  (if-let [accept (get-in request [:headers "accept"])]
    (let [v4-accept-val "binary"
          vals (str/split accept #"\s*,\s*")                ; BAD! see RFC-2616
          munged-accept (->> vals
                          (replace {"s" v4-accept-val "pson" v4-accept-val})
                          (distinct)
                          (str/join ", "))]
      (assoc-in request [:headers "accept"] munged-accept))
    request))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Handler wrapper

(defn request-compatibility-wrapper
  "Given a Puppet 3.x request, convert it to a Puppet 4.x request."
  [handler mount-point api-version request]
  (let [{{environment :environment} :params
         path-info                  :path-info
         query-string               :query-string} request
        query-params (if (str/blank? query-string)
                       {}
                       (ring-codec/form-decode query-string))]
    (let [compat-request
          (-> request
              (map-accept-raw-to-binary)
              (assoc :path-info    (str "/" api-version path-info)
                     :context      mount-point
                     :uri          (str mount-point "/" api-version path-info)
                     :query-string (ring-codec/form-encode
                                     (merge
                                       query-params
                                       (when environment {:environment environment}))))
              (dissoc :params :route-params))]
      (handler compat-request))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v2_0-routes
  "Creates the compojure routes to handle the 3.X master's '/v2.0' routes."
  [request-handler]
  (compojure/routes
    (compojure/GET "/environments" request
                   (request-handler request))))

(defn legacy-master-routes
  [master-request-handler ca-request-handler]
  (compojure/routes
    (compojure/GET "/node/*" request (master-request-handler request))
    (compojure/GET "/facts/*" request (master-request-handler request))
    (compojure/GET "/file_content/*" request (master-request-handler request))
    (compojure/GET "/file_metadatas/*" request (master-request-handler request))
    (compojure/GET "/file_metadata/*" request (master-request-handler request))
    (compojure/GET "/file_bucket_file/*" request
      (master-request-handler (map-accept-s-pson-to-binary request)))
    ;; TODO: file_bucket_file request PUTs from Puppet agents currently use a
    ;; Content-Type of 'text/plain', which, per HTTP specification, would imply
    ;; a default character encoding of ISO-8859-1 or US-ASCII be used to decode
    ;; the data.  This would be incorrect to do in this case, however, because
    ;; the actual payload is "binary".  Coercing this to
    ;; "application/octet-stream" for now as this is synonymous with "binary".
    ;; This should be removed when/if Puppet agents start using an appropriate
    ;; Content-Type to describe the input payload - see PUP-3812 for the core
    ;; Puppet work and SERVER-294 for the related Puppet Server work that
    ;; would be done.
    (compojure/PUT "/file_bucket_file/*" request
      (master-request-handler
        (-> request
          (map-accept-s-pson-to-binary)
          (assoc :content-type "application/octet-stream") ; deprecated in ring >= 1.2
          (assoc-in [:headers "content-type"] "application/octet-stream")))) ; ring >= 1.2 spec
    (compojure/HEAD "/file_bucket_file/*" request
      (master-request-handler (map-accept-s-pson-to-binary request)))
    (compojure/GET "/catalog/*" request (master-request-handler request))
    (compojure/POST "/catalog/*" request (master-request-handler request))
    (compojure/PUT "/report/*" request (master-request-handler request))
    (compojure/GET "/resource_type/*" request (master-request-handler request))
    (compojure/GET "/resource_types/*" request (master-request-handler request))
    (compojure/GET "/status/*" request (master-request-handler request))

    ;; legacy CA routes
    (compojure/ANY "/certificate_status/*" request (ca-request-handler request))
    (compojure/ANY "/certificate_statuses/*" request (ca-request-handler request))
    (compojure/GET "/certificate/*" request (ca-request-handler request))
    (compojure/GET "/certificate_revocation_list/*" request (ca-request-handler request))
    (compojure/GET "/certificate_request/*" request (ca-request-handler request))
    (compojure/PUT "/certificate_request/*" request (ca-request-handler request))
    (compojure/GET "/certificate_requests/*" request (ca-request-handler request))))

(defn legacy-routes
  "Creates all of the compojure routes for the master."
  [master-request-handler ca-request-handler]
  (compojure/routes
    (compojure/context "/v2.0" request
                       (v2_0-routes master-request-handler))
    (compojure/context "/:environment" [environment]
                       (legacy-master-routes master-request-handler ca-request-handler))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn build-ring-handler
  [master-handler master-mount master-api-version
   ca-handler ca-mount ca-api-version]
  (legacy-routes
    #(request-compatibility-wrapper master-handler master-mount master-api-version %)
    #(request-compatibility-wrapper ca-handler ca-mount ca-api-version %)))
