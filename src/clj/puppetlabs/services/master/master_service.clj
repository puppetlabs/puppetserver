(ns puppetlabs.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [ring.middleware.params :as ring]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.metrics.http :as http-metrics]
            [puppetlabs.services.protocols.master :as master]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.services.master.master-core :as master-core]
            [clojure.string :as str]))

(def master-service-status-version 1)

;; Default list of allowed histograms/timers
(def default-metrics-allowed-hists
  ["http.active-histo"
   "http.puppet-v3-catalog-/*/-requests"
   "http.puppet-v3-environment-/*/-requests"
   "http.puppet-v3-environment_classes-/*/-requests"
   "http.puppet-v3-environments-requests"
   "http.puppet-v3-file_bucket_file-/*/-requests"
   "http.puppet-v3-file_content-/*/-requests"
   "http.puppet-v3-file_metadata-/*/-requests"
   "http.puppet-v3-file_metadatas-/*/-requests"
   "http.puppet-v3-node-/*/-requests"
   "http.puppet-v3-report-/*/-requests"
   "http.puppet-v3-static_file_content-/*/-requests"])

;; Default list of allowed values/counts
(def default-metrics-allowed-vals
  ["http.active-requests"
   "http.puppet-v3-catalog-/*/-percentage"
   "http.puppet-v3-environment-/*/-percentage"
   "http.puppet-v3-environment_classes-/*/-percentage"
   "http.puppet-v3-environments-percentage"
   "http.puppet-v3-file_bucket_file-/*/-percentage"
   "http.puppet-v3-file_content-/*/-percentage"
   "http.puppet-v3-file_metadata-/*/-percentage"
   "http.puppet-v3-file_metadatas-/*/-percentage"
   "http.puppet-v3-node-/*/-percentage"
   "http.puppet-v3-report-/*/-percentage"
   "http.puppet-v3-static_file_content-/*/-percentage"
   "http.puppet-v3-status-/*/-percentage"
   "http.total-requests"
   ; num-cpus is registered in trapperkeeper-comidi-metrics, see
   ; https://github.com/puppetlabs/trapperkeeper-comidi-metrics/blob/0.1.1/src/puppetlabs/metrics/http.clj#L117-L120
   "num-cpus"])

;; List of allowed jvm gauges/values
(def default-jvm-metrics-allowed
  ["uptime"
   "memory.heap.committed"
   "memory.heap.init"
   "memory.heap.max"
   "memory.heap.used"
   "memory.non-heap.committed"
   "memory.non-heap.init"
   "memory.non-heap.max"
   "memory.non-heap.used"
   "memory.total.committed"
   "memory.total.init"
   "memory.total.max"
   "memory.total.used"])

(def http-client-metrics-allowed-hists
  (map #(format "http-client.experimental.with-metric-id.%s.full-response" (str/join "." %))
       master-core/puppet-server-http-client-metrics-for-status))

(def default-metrics-allowed
  (concat
   default-metrics-allowed-hists
   default-metrics-allowed-vals
   default-jvm-metrics-allowed
   http-client-metrics-allowed-hists))

(defservice master-service
  master/MasterService
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]
   [:MetricsService get-metrics-registry get-server-id update-registry-settings]
   [:CaService initialize-master-ssl! retrieve-ca-cert! retrieve-ca-crl! get-auth-handler]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]
   [:SchedulerService interspaced]
   [:StatusService register-status]
   [:VersionedCodeService get-code-content current-code-id]]
  (init
   [this context]
   (core/validate-memory-requirements!)
   (let [config (get-config)
         route-config (core/get-master-route-config ::master-service config)
         path (core/get-master-mount ::master-service route-config)
         certname (get-in config [:puppetserver :certname])
         localcacert (get-in config [:puppetserver :localcacert])
         puppet-version (get-in config [:puppetserver :puppet-version])
         settings (ca/config->master-settings config)
         metrics-server-id (get-server-id)
         jruby-service (tk-services/get-service this :JRubyPuppetService)
         use-legacy-auth-conf (get-in config
                                      [:jruby-puppet :use-legacy-auth-conf]
                                      false)
         environment-class-cache-enabled (get-in config
                                                 [:jruby-puppet
                                                  :environment-class-cache-enabled]
                                                 false)
         ring-app (comidi/routes
                   (core/construct-root-routes puppet-version
                                               use-legacy-auth-conf
                                               jruby-service
                                               get-code-content
                                               current-code-id
                                               handle-request
                                               (get-auth-handler)
                                               environment-class-cache-enabled))
         routes (comidi/context path ring-app)
         route-metadata (comidi/route-metadata routes)
         comidi-handler (comidi/routes->handler routes)
         registry (get-metrics-registry :puppetserver)
         http-metrics (http-metrics/initialize-http-metrics!
                       registry
                       metrics-server-id
                       route-metadata)
         http-client-metric-ids-for-status (atom master-core/puppet-server-http-client-metrics-for-status)
         ring-handler (-> comidi-handler
                          (http-metrics/wrap-with-request-metrics http-metrics)
                          (comidi/wrap-with-route-metadata routes))
         hostcrl (get-in config [:puppetserver :hostcrl])]

     (retrieve-ca-cert! localcacert)
     (retrieve-ca-crl! hostcrl)
     (initialize-master-ssl! settings certname)

     (core/register-jvm-metrics! registry metrics-server-id)

     (update-registry-settings :puppetserver
                               {:default-metrics-allowed default-metrics-allowed})

     (log/info (i18n/trs "Master Service adding ring handlers"))

     ;; if the webrouting config uses the old-style config where
     ;; there is a single key with a route-id, we need to deal with that
     ;; for backward compat.  We have a hard-coded assumption that this route-id
     ;; must be `master-routes`.  In Puppet Server 2.0, we also supported a
     ;; key called `invalid-in-puppet-4` in the same route config, even though
     ;; that key is no longer used for Puppet Server 2.1 and later.  We
     ;; should be able to remove this hack as soon as we are able to get rid
     ;; of the legacy routes.
     (if (and (map? route-config)
              (contains? route-config :master-routes))
       (add-ring-handler this
                         ring-handler
                         {:route-id :master-routes
                          :normalize-request-uri true})
       (add-ring-handler this
                         ring-handler
                         {:normalize-request-uri true}))

     (register-status
      "master"
      (status-core/get-artifact-version "puppetlabs" "puppetserver")
      master-service-status-version
      (partial core/v1-status http-metrics http-client-metric-ids-for-status registry))
     (-> context
         (assoc :http-metrics http-metrics)
         (assoc :http-client-metric-ids-for-status http-client-metric-ids-for-status))))
  (start
    [this context]
    (log/info (i18n/trs "Puppet Server has successfully started and is now ready to handle requests"))
    context)

  (add-metric-ids-to-http-client-metrics-list!
   [this metric-ids-to-add]
   (let [metric-ids-from-context (:http-client-metric-ids-for-status
                                  (tk-services/service-context this))]
     (master-core/add-metric-ids-to-http-client-metrics-list! metric-ids-from-context
                                                              metric-ids-to-add))))
