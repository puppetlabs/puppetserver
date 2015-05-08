(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream)
           (clojure.lang IFn))
  (:require [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.comidi :as comidi]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v2_0-routes
  "Creates the web routes to handle the master's '/v2.0' routes."
  [request-handler]
  (comidi/routes
    (comidi/GET "/environments" request
                   (request-handler request))))

(defn legacy-routes
  "Creates the web routes to handle the master's 'legacy' routes
   - ie, any route without a version in its path (eg, /v2.0/whatever) - but
   excluding the CA-related endpoints, which are handled separately by the
   CA service."
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
    (comidi/PUT ["/file_bucket_file/" [#".*" :rest]] request
                   (request-handler (assoc request
                                           :content-type
                                           "application/octet-stream")))

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
    (comidi/GET ["/status/" [#".*" :rest]] request
                   (request-handler request))

    ;; TODO: when we get rid of the legacy dashboard after 3.4, we should remove
    ;; this endpoint as well.  It makes more sense for this type of query to be
    ;; directed to PuppetDB.
    (comidi/GET ["/facts_search/" [#".*" :rest]] request
                   (request-handler request))))

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

(defn root-routes
  "Creates all of the web routes for the master."
  [request-handler]
  (comidi/routes
    (comidi/context "/v2.0"
                     (v2_0-routes request-handler))
    (comidi/context ["/" :environment]
                     (legacy-routes request-handler))))

(schema/defn ^:always-validate
  wrap-middleware :- IFn
  [handler :- IFn
   puppet-version :- schema/Str]
  (-> handler
      ringutils/wrap-request-logging
      ringutils/wrap-response-logging
      (ringutils/wrap-with-puppet-version-header puppet-version)))

