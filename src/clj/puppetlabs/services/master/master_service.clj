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
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]))

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
   "http.puppet-v3-resource_type-/*/-percentage"
   "http.puppet-v3-resource_types-/*/-percentage"
   "http.puppet-v3-static_file_content-/*/-percentage"
   "http.puppet-v3-status-/*/-percentage"
   "http.total-requests"
   ; num-cpus is registered in trapperkeeper-comidi-metrics, see
   ; https://github.com/puppetlabs/trapperkeeper-comidi-metrics/blob/0.1.1/src/puppetlabs/metrics/http.clj#L117-L120
   "num-cpus"])

(def default-metrics-allowed
  (concat
   default-metrics-allowed-hists
   default-metrics-allowed-vals))

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
   [:VersionedCodeService get-code-content]]
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
                                      true)
         clojure-handler (fn [handler]
                           (core/get-wrapped-handler
                            (ring/wrap-params handler)
                            (get-auth-handler)
                            puppet-version))
         environment-class-cache-enabled (get-in config
                                                 [:jruby-puppet
                                                  :environment-class-cache-enabled]
                                                 false)
         ring-app (comidi/routes
                   (core/construct-root-routes puppet-version
                                               use-legacy-auth-conf
                                               jruby-service
                                               get-code-content
                                               handle-request
                                               (get-auth-handler)
                                               environment-class-cache-enabled)
                   (-> (core/experimental-routes path)
                       (comidi/wrap-routes clojure-handler)))
         routes (comidi/context path ring-app)
         route-metadata (comidi/route-metadata routes)
         comidi-handler (comidi/routes->handler routes)
         registry (get-metrics-registry :puppetserver)
         metrics (http-metrics/initialize-http-metrics!
                  registry
                  metrics-server-id
                  route-metadata)
         ring-handler (-> comidi-handler
                          (http-metrics/wrap-with-request-metrics metrics)
                          (comidi/wrap-with-route-metadata routes))
         hostcrl (get-in config [:puppetserver :hostcrl])]

     (retrieve-ca-cert! localcacert)
     (retrieve-ca-crl! hostcrl)
     (initialize-master-ssl! settings certname)

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
      (partial core/v1-status metrics))
     (assoc context :http-metrics metrics)))
  (start
    [this context]
    (log/info (i18n/trs "Puppet Server has successfully started and is now ready to handle requests"))
    context))
