(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream))
  (:require [compojure.core :as compojure]
            [compojure.route :as route]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.services.config.puppet-server-config-core :as config]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-API-versions
  "v3")

(def puppet-ca-API-versions
  "v1")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v3-routes
  "Creates the compojure routes to handle the master's '/v3' routes, which
   includes '/environments' and the non-CA indirected routes. The CA-related
   endpoints are handled separately by the CA service."
  [request-handler]
  (compojure/routes
    (compojure/GET "/node/*" request
                   (request-handler request))
    (compojure/GET "/facts/*" request
                   (request-handler request))
    (compojure/GET "/file_content/*" request
                   (request-handler request))
    (compojure/GET "/file_metadatas/*" request
                   (request-handler request))
    (compojure/GET "/file_metadata/*" request
                   (request-handler request))
    (compojure/GET "/file_bucket_file/*" request
                   (request-handler request))
    (compojure/PUT "/file_bucket_file/*" request
                   (request-handler request))
    (compojure/HEAD "/file_bucket_file/*" request
                   (request-handler request))
    (compojure/GET "/catalog/*" request
                   (request-handler request))
    (compojure/POST "/catalog/*" request
                    (request-handler request))
    (compojure/PUT "/report/*" request
                   (request-handler request))
    (compojure/GET "/resource_type/*" request
                   (request-handler request))
    (compojure/GET "/resource_types/*" request
                   (request-handler request))
    (compojure/GET "/environments" request
                   (request-handler request))

    ;; TODO: when we get rid of the legacy dashboard after 3.4, we should remove
    ;; this endpoint as well.  It makes more sense for this type of query to be
    ;; directed to PuppetDB.
    (compojure/GET "/facts_search/*" request
                   (request-handler request))))

(defn root-routes
  "Creates all of the compojure routes for the master."
  [request-handler]
  (compojure/routes
    (compojure/context "/v3" request
                       (v3-routes request-handler))
    (route/not-found "Not Found")))

(defn construct-404-error-message
  [jruby-service product-name]
  (str "Error: Invalid URL - Puppet Server expects requests that conform to the "
       "/puppet and /puppet-ca APIs.\n\n"
       "Note that Puppet 3 agents aren't compatible with this version; if you're "
       "running Puppet 3, you must either upgrade your agents to match the server "
       "or point them to a server running Puppet 3.\n\n"
       "Server Info:\n"
       "  Puppet Server version: " (version-check/get-version-string (:artifact-id product-name) (:group-id product-name)) "\n"
       "  Puppet version: " (:puppet-version (config/get-puppet-config jruby-service)) "\n"
       "  Supported /puppet API versions: " puppet-API-versions "\n"
       "  Supported /puppet-ca API versions: " puppet-ca-API-versions))

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

(defn build-ring-handler
  "Creates the entire compojure application (all routes and middleware)."
  [request-handler puppet-version]
  {:pre [(fn? request-handler)]}
  (-> (root-routes request-handler)
      ringutils/wrap-exception-handling
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging
      (ringutils/wrap-with-puppet-version-header puppet-version)))

(defn construct-invalid-request-handler
  "Constructs a ring handler to handle an incorrectly formatted request and indicate to the user
   they need to update to Puppet 4"
  [error-message]
  (fn
    [_]
    {:status 404
     :body   error-message}))
