(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream))
  (:require [compojure.core :as compojure]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]))

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

    ;; TODO: when we get rid of the legacy dashboard after 3.4, we should remove
    ;; this endpoint as well.  It makes more sense for this type of query to be
    ;; directed to PuppetDB.
    (compojure/GET "/facts_search/*" request
                   (request-handler request))))

(defn root-routes
  "Creates all of the compojure routes for the master."
  [request-handler]
  (compojure/routes
    (compojure/context "/v2.0" request
                       (v2_0-routes request-handler))
    (compojure/context "/:environment" [environment]
                       (legacy-routes request-handler))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Lifecycle Helper Functions

(defn validate-memory-requirements!
  "On Linux Distributions, parses the /proc/meminfo file to determine
   the total amount of System RAM, and throws an exception if that
   is less than 1.1 times the maximum heap size of the JVM. This is done
   so that the JVM doesn't fail later due to an Out of Memory error."
  []
  (when (fs/exists? "/proc/meminfo")
    ; Due to OpenJDK Issue JDK-7132461
    ; (https://bugs.openjdk.java.net/browse/JDK-7132461),
    ; we have to open and slurp a FileInputStream object rather than
    ; slurping the file directly, since directly slurping the file
    ; causes a call to be made to FileInputStream.available().
    (with-open [mem-info-file (FileInputStream. "/proc/meminfo")]
      (let [heap-size (/ (.maxMemory (Runtime/getRuntime)) 1024)
            mem-size (Integer. (second (re-find #"MemTotal:\s+(\d+)\s+\S+"
                                                (slurp mem-info-file))))
            required-mem-size (/ heap-size 0.9)]
        (when (< mem-size required-mem-size)
          (throw (Error.
                   (str "Not enough RAM. Puppet Server requires at least "
                        (int (/ required-mem-size 1024.0))
                        "MB of RAM."))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn build-ring-handler
  "Creates the entire compojure application (all routes and middleware)."
  [request-handler]
  {:pre [(fn? request-handler)]}
  (-> (root-routes request-handler)
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging))
