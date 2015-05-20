(ns puppetlabs.services.legacy-routes.legacy-routes-core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as comidi]
            [ring.util.codec :as ring-codec]
            [ring.util.response :as ring-response]
            [compojure.route :as route]
            [clojure.tools.logging :as log]
            [clojure.string :as string]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; v3 => v4 header translation support functions

; TODO: Improve the naive parsing here with a real library like
; https://github.com/ToBeReplaced/http-accept-headers
(defn- map-accept-header
  "Convert Accept: raw, s media types to Accept: binary.  NOTE: This method does
  not conform to RFC-2616 Accept: header specification.

  The split on `,` is naive, the method should take into account `;` parameters
  and such to be RFC compliant, but puppet agent does not use this format so
  implementing the full spec is unnecessary."
  [request]
  (if-let [accept (get-in request [:headers "accept"])]
    (let [vals (map str/lower-case (str/split accept #"\s*,\s*"))]
      (assoc-in request [:headers "accept"]
        (->> vals
          (replace {"raw" "binary", "s" "binary"})
          (distinct)
          (str/join ", "))))
    request))

(defn- respond-with-content-type-text-plain
  "Return a new ring handler with a ring middleware wrapped around `handler`
  that unconditionally adds the Content-Type: text/plain header to the
  response."
  [handler]
  (fn [request]
    (ring-response/content-type (handler request) "text/plain")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Handler wrapper

(defn request-compatibility-wrapper
  "Given a Puppet 3.x request, convert it to a Puppet 4.x request."
  [handler mount-point api-version request]
  (let [{{environment :environment} :params
         uri :uri
         query-string               :query-string} request
        path-info (str "/" (-> (string/split uri #"/" 3) (nth 2)))
        query-params (if (str/blank? query-string)
                       {}
                       (ring-codec/form-decode query-string))]
    (let [compat-request
          (-> request
              (map-accept-header)
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
  (comidi/routes
    (comidi/GET "/environments" request
      (request-handler request))))

(defn legacy-master-routes
  [master-request-handler ca-request-handler]
  (comidi/routes
    (comidi/GET ["/node/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/file_content/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/file_metadatas/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/file_metadata/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/file_bucket_file/" [#".*" :rest]] request
      ((respond-with-content-type-text-plain master-request-handler) request))
    ;; Coercing Content-Type to "application/octet-stream" for Puppet 4
    ;; compatibility.
    (comidi/PUT ["/file_bucket_file/" [#".*" :rest]] request
      (master-request-handler
        (-> request
          (assoc :content-type "application/octet-stream") ; deprecated in ring >= 1.2
          (assoc-in [:headers "content-type"] "application/octet-stream")))) ; ring >= 1.2 spec
    (comidi/HEAD ["/file_bucket_file/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/catalog/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/POST ["/catalog/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/PUT ["/report/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/resource_type/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/resource_types/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/status/" [#".*" :rest]] request
      (master-request-handler request))

    ;; legacy CA routes
    (comidi/ANY ["/certificate_status/" [#".*" :rest]] request
      (ca-request-handler request))
    (comidi/ANY ["/certificate_statuses/" [#".*" :rest]] request
      (ca-request-handler request))
    (comidi/GET ["/certificate/" [#".*" :rest]] request
      (ca-request-handler request))
    (comidi/GET ["/certificate_revocation_list/" [#".*" :rest]] request
      (ca-request-handler request))
    (comidi/GET ["/certificate_request/" [#".*" :rest]] request
      (ca-request-handler request))
    (comidi/PUT ["/certificate_request/" [#".*" :rest]] request
      (ca-request-handler request))))

(defn legacy-routes
  "Creates all of the comidi routes for the master."
  [master-request-handler ca-request-handler]
  (comidi/routes
    (comidi/context "/v2.0"
      (v2_0-routes master-request-handler))
    (comidi/context ["/" :environment]
      (legacy-master-routes master-request-handler ca-request-handler))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn build-ring-handler
  [master-handler master-mount master-api-version
   ca-handler ca-mount ca-api-version]
  (comidi/routes->handler
    (legacy-routes
      #(request-compatibility-wrapper
        master-handler master-mount master-api-version %)
      #(request-compatibility-wrapper
        ca-handler ca-mount ca-api-version %))))
