(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream)
           (clojure.lang IFn))
  (:require [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.comidi :as comidi]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-API-versions
  "v3")

(def puppet-ca-API-versions
  "v1")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v3-routes
  "Creates the routes to handle the master's '/v3' routes, which
   includes '/environments' and the non-CA indirected routes. The CA-related
   endpoints are handled separately by the CA service."
  [request-handler]
  (comidi/routes
    (comidi/GET ["/node/" [#".*" :rest]] request
                   (request-handler request))
    (comidi/GET ["/facts/" [#".*" :rest]] request
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
                   (request-handler request))
    (comidi/POST ["/catalog/" [#".*" :rest]] request
                    (request-handler request))
    (comidi/PUT ["/report/" [#".*" :rest]] request
                   (request-handler request))
    (comidi/GET ["/resource_type/" [#".*" :rest]] request
                   (request-handler request))
    (comidi/GET ["/resource_types/" [#".*" :rest]] request
                   (request-handler request))
    (comidi/GET ["/environment/" [#".*" :rest]] request
                   (request-handler request))
    (comidi/GET "/environments" request
                   (request-handler request))
    (comidi/GET ["/status/" [#".*" :rest]] request
                   (request-handler request))

    ;; TODO: when we get rid of the legacy dashboard after 3.4, we should remove
    ;; this endpoint as well.  It makes more sense for this type of query to be
    ;; directed to PuppetDB.
    (comidi/GET ["/facts_search/" [#".*" :rest]] request
                   (request-handler request))))

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
                 (str "Not enough available RAM (" (int (/ mem-size 1024.0))
                      "MB) to safely accommodate the configured JVM heap "
                      "size of " (int (/ heap-size 1024.0)) "MB.  "
                      "Puppet Server requires at least "
                      (int (/ required-mem-size 1024.0))
                      "MB of available RAM given this heap size, computed as "
                      "1.1 * max heap (-Xmx).  Either increase available "
                      "memory or decrease the configured heap size by "
                      "reducing the -Xms and -Xmx values in JAVA_ARGS in "
                      "/etc/sysconfig/puppetserver on EL systems or "
                      "/etc/default/puppetserver on Debian systems.")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn root-routes
  "Creates all of the web routes for the master."
  [request-handler]
  (comidi/routes
    (comidi/context "/v3"
                    (v3-routes request-handler))
    (comidi/not-found "Not Found")))

(schema/defn ^:always-validate
  wrap-middleware :- IFn
  [handler :- IFn
   puppet-version :- schema/Str]
  (-> handler
      ringutils/wrap-exception-handling
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging
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
    (and (map? config-route) (contains? config-route :master-routes))
    (:master-routes config-route)

    (string? config-route)
    config-route

    :else
    (throw (IllegalArgumentException.
             (str "Route not found for service " master-ns)))))
