(ns puppetlabs.services.legacy-routes.legacy-routes-core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as comidi]
            [ring.util.codec :as ring-codec]
            [ring.util.response :as ring-response]
            [compojure.route :as route]
            [clojure.tools.logging :as log]
            [schema.core :as schema])
  (:import (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def LegacyHandlerInfo
  "A map of the three pieces of data needed to implement a legacy re-routing
  ring handler."
  {:mount schema/Str
   :handler IFn
   :api-version schema/Str})

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

(defn- decode-query-string
  "Parses the given query string into a map. If `query-str` is nil then
  an empty map is returned."
  [query-str]
  {:pre [(or (nil? query-str) (string? query-str))]
   :post [(map? %)]}
  (if (str/blank? query-str)
    {}
    (ring-codec/form-decode query-str)))

(defn- add-query-param
  "Given a query string or map of query parameters, add the given key and
  value to it. If the given query string is empty or nil then the resulting
  string will be the single KEY=VALUE expression. If the key already exists in
  the query string its value will be replaced."
  [query-params key value]
  {:pre [(or (map? query-params) (string? query-params) (nil? query-params))
         (or (string? key) (keyword? key))
         (string? value)]
   :post [(string? %)]}
  (let [param-pairs (if (not (map? query-params))
                      (decode-query-string query-params)
                      query-params)]
    (ring-codec/form-encode (assoc param-pairs key value))))

(defn- add-source-permissions-param
  "Add the source_permissions header to the request. This is needed since
  this was considered a default in Puppet 3.x and is now turned off by default
  in Puppet 4.x. See SERVER-684."
  [handler]
  (fn [request]
    (let [{:keys [query-string]} request
          query-keys (decode-query-string query-string)
          source-perms-val (or (get query-keys "source_permissions") "use")
          updated-request (assoc request :query-string
                            (add-query-param
                              query-keys "source_permissions" source-perms-val))]
      (handler updated-request))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Handler wrapper

(schema/defn ^:always-validate
  request-compatibility-wrapper :- IFn
  "Given a Puppet 3.x request, convert it to a Puppet 4.x request."
  [{:keys [handler mount api-version]} :- LegacyHandlerInfo
   request]
  (let [{{environment :environment} :params
         uri :uri
         query-string :query-string} request
        path-info (str "/" (-> (str/split uri #"/" 3) (nth 2)))]
    (let [compat-request
          (-> request
              (map-accept-header)
              (assoc :path-info (str "/" api-version path-info)
                     :context mount
                     :uri (str mount "/" api-version path-info)
                     :query-string (if environment
                                     (add-query-param query-string
                                       "environment" environment)
                                     query-string))
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
  [master-request-handler]
  (comidi/routes
    (comidi/GET ["/node/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/file_content/" [#".*" :rest]] request
      (master-request-handler request))
    (comidi/GET ["/file_metadatas/" [#".*" :rest]] request
      ((add-source-permissions-param master-request-handler) request))
    (comidi/GET ["/file_metadata/" [#".*" :rest]] request
      ((add-source-permissions-param master-request-handler) request))
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
    (comidi/GET ["/status/" [#".*" :rest]] request
      (master-request-handler request))))

(defn legacy-ca-routes
  [ca-request-handler]
  (comidi/routes
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn add-root-path-to-route-id :- (schema/pred fn?)
  [handler :- (schema/pred fn?)
   path :- schema/Str]
  (fn [request]
    (handler (update-in request [:route-info :route-id]
                        #(str (subs path 1) "-" %)))))

(schema/defn ^:always-validate
  build-ring-handler :- IFn
  [master-handler-info :- LegacyHandlerInfo
   ca-handler-info :- (schema/maybe LegacyHandlerInfo)]
  (let [master-handler #(request-compatibility-wrapper master-handler-info %)
        master-routes (legacy-master-routes master-handler)
        ca-routes (when ca-handler-info
                    (legacy-ca-routes
                      #(request-compatibility-wrapper ca-handler-info %)))
        root-routes (vec (filter (complement nil?)
                      [master-routes ca-routes]))]
    (comidi/routes->handler
      (comidi/routes
        (comidi/context "/v2.0"
          (v2_0-routes master-handler))
        [["/" :environment] root-routes]))))
