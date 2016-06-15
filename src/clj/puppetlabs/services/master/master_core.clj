(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream)
           (clojure.lang IFn)
           (java.util Map Map$Entry)
           (org.jruby RubySymbol)
           (org.eclipse.jetty.util URIUtil))
  (:require [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.puppetserver.common :as ps-common]
            [puppetlabs.comidi :as comidi]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.puppetserver.jruby-request :as jruby-request]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.core :as middleware]
            [puppetlabs.ring-middleware.utils :as middleware-utils]
            [cheshire.core :as cheshire]
            [clojure.string :as str]
            [bidi.schema :as bidi-schema]
            [ring.middleware.params :as ring]
            [puppetlabs.i18n.core :as i18n :refer [trs tru]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-API-version
  "v3")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(def EnvironmentClassesFileClassParameter
  "Schema for an individual parameter found in a class defined within an
  environment_classes file.  Includes the name of the parameter.  Optionally,
  if available in the parameter definition, includes the type, source text for
  the parameter's default value, and a literal (primitive data type)
  representation of the parameter's default value.

  For example, if a class parameter were defined like this in a Puppet
  manifest ...

    Integer $someint = 3

  ... then the map representation of that parameter would be:

  {:name \"someint\",
   :type \"Integer\",
   :default_source \"3\",
   :default_literal 3
  }

  For a parameter default value which contains an expression - something that
  cannot be coerced into a primitive data type - the map representation omits
  the default_literal.  For example, this parameter definition in a Puppet
  manifest ...

   String $osfam = \"$::osfamily\"

  ... would produce a map representation which looks like this ...

  {:name \"osfam\",
   :type \"String\",
   :default_source \"\"$::osfamily\"\",
  }

  The data types that could be used in the value for a :default_literal may
  vary over time, as the Puppet language continues to evolve.  For this
  reason, the more permissive schema/Any is used here.  General data types
  that we could see based on current types that the Puppet language allows
  today are in the table below:

  Puppet          | :default_literal value
  ================================================
  String          | java.lang.String
  Boolean         | java.lang.Boolean
  Float/Numeric   | java.lang.Double
  Integer/Numeric | java.lang.Long
  Array           | clojure.lang.LazySeq
  Hash            | clojure.lang.PersistentTreeMap

  For the Regexp, Undef, Default, any Hash objects (top-level or nested within
  another Array or Hash) whose keys are not of type String, and any Array
  or Hash objects (top-level or nested within another Array or Hash) which
  contain a Regexp, Undef, or Default typed value, the :default_literal value
  will not be populated.  These are not populated because they cannot be
  represented in JSON transfer without some loss of fidelity as compared to what
  the original data type from the manifest was."
  {:name schema/Str
   (schema/optional-key :type) schema/Str
   (schema/optional-key :default_source) schema/Str
   (schema/optional-key :default_literal) schema/Any})

(def EnvironmentClassesFileClass
  "Schema for an individual class found within an environment_classes file
  entry.  Includes the name of the class and a vector of information about
  each parameter found in the class definition."
  {:name schema/Str
   :params [EnvironmentClassesFileClassParameter]})

(def EnvironmentClassesFileWithError
  "Schema for an environment_classes file that could not be parsed.  Includes
   the path to the file and an error string containing details about the
   problem encountered during parsing."
  {:path schema/Str
   :error schema/Str})

(def EnvironmentClassesFileWithClasses
  "Schema for an environment_classes file that was successfully parsed.
  Includes the path to the file and a vector of information about each class
  found in the file."
  {:path schema/Str
   :classes [EnvironmentClassesFileClass]})

(def EnvironmentClassesFileEntry
  "Schema for an individual file entry which is part of the return payload
  for an environment_classes request."
  (schema/conditional
   #(contains? % :error) EnvironmentClassesFileWithError
   #(contains? % :classes) EnvironmentClassesFileWithClasses))

(def EnvironmentClassesInfo
  "Schema for the return payload an environment_classes request."
  {:name schema/Str
   :files [EnvironmentClassesFileEntry]})

(defn obj-or-ruby-symbol-as-string
  "If the supplied object is of type RubySymbol, returns a string
  representation of the RubySymbol.  Otherwise, just returns the original
  object."
  [obj]
  (if (instance? RubySymbol obj)
    (.asJavaString obj)
    obj))

(defn obj->keyword
  "Attempt to convert the supplied object to a Clojure keyword.  On failure
  to do so, throws an IllegalArgumentException."
  [obj]
  (if-let [obj-as-keyword (-> obj obj-or-ruby-symbol-as-string keyword)]
    obj-as-keyword
    (throw (IllegalArgumentException.
            (trs "Object cannot be coerced to a keyword: {0}" obj)))))

(defn sort-nested-environment-class-info-maps
  "For a data structure, recursively sort any nested maps and sets descending
  into map values, lists, vectors and set members as well. The result should be
  that all maps in the data structure become explicitly sorted with natural
  ordering. This can be used before serialization to ensure predictable
  serialization.

  The returned data structure is not a transient so it is still able to be
  modified, therefore caution should be taken to avoid modification else the
  data will lose its sorted status.

  This function was copypasta'd from clj-kitchensink's core/sort-nested-maps.
  sort-nested-maps can only deep sort a structure that contains native Clojure
  types, whereas this function includes a couple of changes which handle the
  sorting of the data structure returned from JRuby for a call to get
  environment class info:

  1) This function sorts keys within any `java.util.Map`, as opposed to just an
     object for which `map?` returns true.

  2) This function attempts to coerces any keys found within a map to a
     Clojure keyword before using `sorted-map` to sort the keys.
     `sorted-map` would otherwise throw an error upon encountering a
     non-keyword type key."
  [data]
  (cond
    (instance? Map data)
    (into (sorted-map) (for [[k v] data]
                         [(obj->keyword k)
                          (sort-nested-environment-class-info-maps v)]))

    (instance? Iterable data)
    (map sort-nested-environment-class-info-maps data)

    :else data))

(schema/defn ^:always-validate
  if-none-match-from-request :- (schema/maybe String)
  "Retrieve the value of an 'If-None-Match' HTTP header from the supplied Ring
  request.  If the header is not found, returns nil."
  [request :- {schema/Keyword schema/Any}]
  ;; SERVER-1153 - For a non-nil value, the characters '--gzip' will be
  ;; stripped from the end of the value which is returned.  The '--gzip'
  ;; characters are added by the Jetty web server to an HTTP Etag response
  ;; header for cases in which the corresponding response payload has been
  ;; gzipped.  Newer versions of Jetty, 9.3.x series, have logic in the
  ;; GzipHandler to strip these characters back off of the If-None-Match header
  ;; value before the Ring request would see it.  The version of Jetty being
  ;; used at the time this code was written (9.2.10), however, did not have this
  ;; logic to strip the "--gzip" characters from the incoming header.  This
  ;; function compensates for that by stripping the characters here - before
  ;; other Puppet Server code would use it. When/if Puppet Server is upgraded to
  ;; a version of trapperkeeper-webserver-jetty9 which is based on Jetty 9.3.x
  ;; or newer, it may be safe to take out the line that removes the '--gzip'
  ;; characters.
  (some-> (rr/get-header request "If-None-Match")
          (str/replace #"--gzip$" "")))

(schema/defn ^:always-validate
  add-path-to-file-entry :- Map
  "Convert the value for a manifest file entry into an appropriate map for
  use in serializing an environment_classes response to JSON."
  [file-detail :- Map
   file-name :- schema/Str]
  (.put file-detail "path" file-name)
  file-detail)

(schema/defn ^:always-validate
  manifest-info-from-jruby->manifest-info-for-json
    :- EnvironmentClassesFileEntry
  "Convert the per-manifest file information received from the jruby service
  into an appropriate map for use in serializing an environment_classes
  response to JSON."
  [file-info :- Map$Entry]
  (-> file-info
      val
      (add-path-to-file-entry (key file-info))
      sort-nested-environment-class-info-maps))

(schema/defn ^:always-validate
  class-info-from-jruby->class-info-for-json :- EnvironmentClassesInfo
  "Convert a class info map received from the jruby service into an
  appropriate map for use in serializing an environment_classes response to
  JSON.  The map that this function returns should be 'sorted' by key - both
  at the top-level and within any nested map - so that it will consistently
  serialize to the exact same content.  For this reason, this function and
  the functions that this function calls use the `sorted-map` and
  `sort-nested-java-maps` helper functions when constructing / translating
  maps."
  [info-from-jruby :- Map
   environment :- schema/Str]
  (->> info-from-jruby
       (map manifest-info-from-jruby->manifest-info-for-json)
       (sort-by :path)
       (vec)
       (sorted-map :name environment :files)))

(schema/defn ^:always-validate
  response-with-etag :- ringutils/RingResponse
  "Create a Ring response, including the supplied 'body' and an HTTP 'Etag'
  header set to the supplied 'etag' parameter."
  [body :- schema/Str
   etag :- schema/Str]
  (-> body
      (rr/response)
      (rr/header "Etag" etag)))

(schema/defn ^:always-validate
  not-modified-response :- ringutils/RingResponse
  "Create an HTTP 304 (Not Modified) response, including an HTTP 'Etag'
  header set to the supplied 'etag' parameter."
  [etag]
  (-> ""
      (response-with-etag etag)
      (rr/status 304)))

(schema/defn ^:always-validate
  environment-class-response! :- ringutils/RingResponse
  "Process the environment class info, returning a Ring response to be
  propagated back up to the caller of the environment_classes endpoint.

  If the specified `environment-class-cache-enabled` is 'true', a SHA-1 hash
  of the class info will be generated.  If the hash is equal to the supplied
  `request-tag`, the response will have an HTTP 304 (Not Modified) status code
  and the response body will be empty.  If the hash is not equal to the supplied
  `request-tag`, the response will have an HTTP 200 (OK) status code and
  the class info, serialized to JSON, will appear in the response body.  The
  newly generated hash code, along with the specified `cache-generation-id`,
  will be passed to the `jruby-service`, to be stored in its environment class
  cache, and will also be returned in the response as the value for an HTTP
  Etag header.

  If the specified `environment-class-cache-enabled` is 'false', no hash
  will be generated for the class info.  The response will always have an
  HTTP 200 (OK) status code and the class info, serialized to JSON, as the
  response body.  An HTTP Etag header will not appear in the response."
  [info-from-jruby :- Map
   environment :- schema/Str
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   request-tag :- (schema/maybe String)
   cache-generation-id :- (schema/maybe schema/Int)
   environment-class-cache-enabled :- schema/Bool]
  (let [info-for-json (class-info-from-jruby->class-info-for-json
                       info-from-jruby
                       environment)]
    (if environment-class-cache-enabled
      (let [info-as-json (cheshire/generate-string info-for-json)
            parsed-tag (ks/utf8-string->sha1 info-as-json)]
        (jruby-protocol/set-environment-class-info-tag!
         jruby-service
         environment
         parsed-tag
         cache-generation-id)
        (if (= parsed-tag request-tag)
          (not-modified-response parsed-tag)
          (-> (response-with-etag info-as-json parsed-tag)
              (rr/content-type "application/json"))))
      (middleware-utils/json-response 200 info-for-json))))

(schema/defn ^:always-validate
  environment-class-info-fn :- IFn
  "Middleware function for constructing a Ring response from an incoming
  request for environment_classes information."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   environment-class-cache-enabled :- schema/Bool]
  (fn [request]
    (let [environment (jruby-request/get-environment-from-request request)
          cache-generation-id
          (jruby-protocol/get-environment-class-info-cache-generation-id!
           jruby-service
           environment)]
      (if-let [class-info
               (jruby-protocol/get-environment-class-info jruby-service
                                                          (:jruby-instance
                                                           request)
                                                          environment)]
        (environment-class-response! class-info
                                     environment
                                     jruby-service
                                     (if-none-match-from-request request)
                                     cache-generation-id
                                     environment-class-cache-enabled)
        (rr/not-found (tru "Could not find environment ''{0}''" environment))))))

(schema/defn ^:always-validate
  wrap-with-etag-check :- IFn
  "Middleware function which validates whether or not the If-None-Match
  header on an incoming environment_classes request matches the last Etag
  computed for the environment whose info is being requested.  If the two
  match, the middleware function returns an HTTP 304 (Not Modified) Ring
  response.  If the two do not match, the request is threaded through to the
  supplied 'f' function."
  [handler :- IFn
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (fn [request]
    (let [environment (jruby-request/get-environment-from-request request)
          request-tag (if-none-match-from-request request)]
      (if (and request-tag
               (= request-tag
                  (jruby-protocol/get-environment-class-info-tag
                   jruby-service
                   environment)))
        (not-modified-response request-tag)
        (handler request)))))

(schema/defn ^:always-validate
  environment-class-handler :- IFn
  "Handler for processing an incoming environment_classes Ring request"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   environment-class-cache-enabled :- schema/Bool]
  (->
   (environment-class-info-fn jruby-service
                              environment-class-cache-enabled)
   (jruby-request/wrap-with-jruby-instance jruby-service)
   (wrap-with-etag-check jruby-service)
   jruby-request/wrap-with-environment-validation
   jruby-request/wrap-with-error-handling))

(schema/defn ^:always-validate valid-static-file-path?
  "Helper function to decide if a static_file_content path is valid.
  The access here is designed to mimic Puppet's file_content endpoint."
  [path :- schema/Str]
  ;; Here, keywords represent a single element in the path. Anything between two '/' counts.
  ;; The second vector takes anything else that might be on the end of the path.
  ;; Below, this corresponds to '*/*/files/**' in a filesystem glob.
  (bidi.bidi/match-route [[#"[^/]+/" :module-name "/files/" [#".+" :rest]] :_]
                         path))

(defn static-file-content-request-handler
  "Returns a function which is the main request handler for the
  /static_file_content endpoint, utilizing the provided implementation of
  `get-code-content`"
  [get-code-content]
  (fn [req]
    (let [environment (get-in req [:params "environment"])
          code-id (get-in req [:params "code_id"])
          file-path (get-in req [:params :rest])]
      (cond
        (some empty? [environment code-id file-path])
        {:status 400
         :body (tru "Error: A /static_file_content request requires an environment, a code-id, and a file-path")}
        (not (nil? (schema/check ps-common/Environment environment)))
        {:status 400
         :body (ps-common/environment-validation-error-msg environment)}

        (not (nil? (schema/check ps-common/CodeId code-id)))
        {:status 400
         :body (ps-common/code-id-validation-error-msg code-id)}

        (not (valid-static-file-path? file-path))
        {:status 403
         :body (tru "Request Denied: A /static_file_content request must be a file within the files directory of a module.")}

        :else
        {:status 200
         :body (get-code-content environment code-id file-path)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(schema/defn ^:always-validate
  v3-ruby-routes :- bidi-schema/RoutePair
  "v3 route tree for the ruby side of the master service."
  [request-handler :- IFn]
  (comidi/routes
   (comidi/GET ["/node/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_content/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_metadatas/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_metadata/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_bucket_file/" [#".*" :rest]] request
               (request-handler request))
   (comidi/PUT ["/file_bucket_file/" [#".*" :rest]] request
               (request-handler request))
   (comidi/HEAD ["/file_bucket_file/" [#".*" :rest]] request
                (request-handler request))

   (comidi/GET ["/catalog/" [#".*" :rest]] request
               (request-handler (assoc request :include-code-id? true)))
   (comidi/POST ["/catalog/" [#".*" :rest]] request
                (request-handler (assoc request :include-code-id? true)))
   (comidi/PUT ["/report/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/resource_type/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/resource_types/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/environment/" [#".*" :environment]] request
               (request-handler (assoc request :include-code-id? true)))
   (comidi/GET "/environments" request
               (request-handler request))
   (comidi/GET ["/status/" [#".*" :rest]] request
               (request-handler request))))

(schema/defn ^:always-validate
  v3-clojure-routes :- bidi-schema/RoutePair
  "v3 route tree for the clojure side of the master service."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (let [environment-class-handler
        (environment-class-handler jruby-service
                                   environment-class-cache-enabled)
        static-file-content-handler
        (static-file-content-request-handler get-code-content-fn)]
    (comidi/routes
     (comidi/GET ["/environment_classes" [#".*" :rest]] request
                 (environment-class-handler request))
     (comidi/GET ["/static_file_content/" [#".*" :rest]] request
                 (static-file-content-handler request)))))

(schema/defn ^:always-validate
  v3-routes :- bidi-schema/RoutePair
  "Creates the routes to handle the master's '/v3' routes, which
   includes '/environments' and the non-CA indirected routes. The CA-related
   endpoints are handled separately by the CA service."
  [ruby-request-handler :- IFn
   clojure-request-wrapper :- IFn
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (comidi/context "/v3"
                  (v3-ruby-routes ruby-request-handler)
                  (comidi/wrap-routes
                   (v3-clojure-routes jruby-service
                                      get-code-content-fn
                                      environment-class-cache-enabled)
                   clojure-request-wrapper )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Lifecycle Helper Functions

(defn meminfo-content
  "Read and return the contents of /proc/meminfo, if it exists.  Otherwise
  return nil."
  []
  (when (fs/exists? "/proc/meminfo")
    ; Due to OpenJDK Issue JDK-7132461
    ; (https://bugs.openjdk.java.net/browse/JDK-7132461),
    ; we have to open and slurp a FileInputStream object rather than
    ; slurping the file directly, since directly slurping the file
    ; causes a call to be made to FileInputStream.available().
    (with-open [mem-info-file (FileInputStream. "/proc/meminfo")]
      (slurp mem-info-file))))

; the current max java heap size (-Xmx) in kB defined for with-redefs in tests
(def max-heap-size (/ (.maxMemory (Runtime/getRuntime)) 1024))

(defn validate-memory-requirements!
  "On Linux Distributions, parses the /proc/meminfo file to determine
   the total amount of System RAM, and throws an exception if that
   is less than 1.1 times the maximum heap size of the JVM. This is done
   so that the JVM doesn't fail later due to an Out of Memory error."
  []
  (when-let [meminfo-file-content (meminfo-content)]
    (let [heap-size max-heap-size
          mem-size (Integer. (second (re-find #"MemTotal:\s+(\d+)\s+\S+"
                                               meminfo-file-content)))
          required-mem-size (* heap-size 1.1)]
      (when (< mem-size required-mem-size)
        (throw (Error.
                (format "%s  %s  %s"
                        (trs "Not enough available RAM ({0}MB) to safely accommodate the configured JVM heap size of {1}MB." (int (/ mem-size 1024.0)))
                        (trs "Puppet Server requires at least {2}MB of available RAM given this heap size, computed as 1.1 * max heap (-Xmx)." (int (/ mem-size 1024.0)))
                        (trs "Either increase available memory or decrease the configured heap size by reducing the -Xms and -Xmx values in JAVA_ARGS in /etc/sysconfig/puppetserver on EL systems or /etc/default/puppetserver on Debian systems." (int (/ mem-size 1024.0))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  root-routes :- bidi-schema/RoutePair
  "Creates all of the web routes for the master."
  [ruby-request-handler :- IFn
   clojure-request-wrapper :- IFn
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (comidi/routes
   (v3-routes ruby-request-handler
              clojure-request-wrapper
              jruby-service
              get-code-content-fn
              environment-class-cache-enabled)))

(schema/defn ^:always-validate
  wrap-middleware :- IFn
  [handler :- IFn
   puppet-version :- schema/Str]
  (-> handler
      (middleware/wrap-uncaught-errors :plain)
      middleware/wrap-request-logging
      i18n/locale-negotiator
      middleware/wrap-response-logging
      (ringutils/wrap-with-puppet-version-header puppet-version)))

(schema/defn ^:always-validate get-master-route-config
  "Get the webserver route configuration for the master service"
  [master-ns :- schema/Keyword
   config :- {schema/Keyword schema/Any}]
  (get-in config [:web-router-service master-ns]))

(schema/defn ^:always-validate
  get-master-mount :- schema/Str
  "Get the webserver mount point that the master service is rooted under"
  [master-ns :- schema/Keyword
   config-route]
  (cond
    ;; if the route config is a map, we need to determine whether it's the
    ;; new-style multi-server config (where there will be a `:route` key and a
    ;; `:server`, key), or the old style where there is a single key that is
    ;; assumed to be our hard-coded route id (`:master-routes`).
    ;; It should be possible to delete this hack (perhaps this entire function)
    ;; when we remove support for legacy routes.
    (and (map? config-route) (or (contains? config-route :route)
                                 (contains? config-route :master-routes)))
    (or (:route config-route)
        (:master-routes config-route))

    (string? config-route)
    config-route

    :else
    (throw (IllegalArgumentException.
             (trs "Route not found for service {0}" master-ns)))))

(schema/defn ^:always-validate
  get-wrapped-handler :- IFn
  "Conditionally wraps route-handler with authorization-fn before calling
  wrap-middleware on the handler. If use-legacy-auth-conf is not specified,
  defaults to wrapping route-handler with authorization-fn."
  ([route-handler :- IFn
    authorization-fn :- IFn
    puppet-version :- schema/Str]
   (get-wrapped-handler route-handler authorization-fn puppet-version false))
  ([route-handler :- IFn
    authorization-fn :- IFn
    puppet-version :- schema/Str
    use-legacy-auth-conf :- schema/Bool]
   (let [handler-maybe-with-authorization (if use-legacy-auth-conf
                                            route-handler
                                            (authorization-fn route-handler))]
     (wrap-middleware handler-maybe-with-authorization puppet-version))))

(schema/defn ^:always-validate
  construct-root-routes :- bidi-schema/RoutePair
  "Creates a wrapped ruby request handler and a clojure request handler,
  then uses those to create all of the web routes for the master."
  [puppet-version :- schema/Str
   use-legacy-auth-conf :- schema/Bool
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content :- IFn
   handle-request :- IFn
   wrap-with-authorization-check :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (let [ruby-request-handler (get-wrapped-handler handle-request
                                                  wrap-with-authorization-check
                                                  puppet-version
                                                  use-legacy-auth-conf)
        clojure-request-wrapper (fn [handler]
                                  (get-wrapped-handler
                                    (ring/wrap-params handler)
                                    wrap-with-authorization-check
                                    puppet-version))]
    (root-routes ruby-request-handler
                 clojure-request-wrapper
                 jruby-service
                 get-code-content
                 environment-class-cache-enabled)))
