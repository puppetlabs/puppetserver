(ns puppetlabs.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [maybe-get-service] :as tk-services]
            [puppetlabs.trapperkeeper.services.protocols.filesystem-watch-service :as watch-protocol]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.puppetserver.legacy-certificate-authority-generation :as ca-gen]
            [puppetlabs.services.ca.certificate-authority-core :as core]
            [puppetlabs.services.protocols.ca :refer [CaService]]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [puppetlabs.rbac-client.protocols.activity :refer [ActivityReportingService] :as activity-proto]))

(def one-day-ms
  (* 24 60 60 1000))

(def ca-scheduled-job-group-id
  :ca-scheduled-job-group-id)

(defn evaluate-crls-for-expiration
  [ca-settings]
  (try
    ;; don't allow exceptions to escape
    (ca/maybe-update-crls-for-expiration ca-settings)
    (catch Exception e
      (log/error e (i18n/trs "Failed to evaluate crls for expiration")))))

(tk/defservice certificate-authority-service
  CaService
   {:required 
    [[:CertificateAuthorityConfigService get-config get-in-config]
      [:WebroutingService add-ring-handler get-route]
      [:AuthorizationService wrap-with-authorization-check]
      [:FilesystemWatchService create-watcher]
      [:StatusService register-status]
      [:SchedulerService interspaced stop-jobs]]
   :optional [ActivityReportingService]}
  (init
    [this context]
    (let [path (get-route this)
          puppet-version (get-in-config [:puppetserver :puppet-version])
          settings (:ca-settings (get-config))
          oid-mappings (:oid-mappings settings)
          auth-handler (fn [request] (wrap-with-authorization-check request {:oid-map oid-mappings}))
          ca-crl-file (.getCanonicalPath (fs/file
                                          (get-in-config [:puppetserver :cacrl])))
          host-crl-file (.getCanonicalPath (fs/file
                                            (get-in-config [:puppetserver :hostcrl])))
          infra-nodes-file (.getCanonicalPath (fs/file (str (fs/parent ca-crl-file) "/infra_inventory.txt")))
          watcher (create-watcher {:recursive false})
          report-activity (if-let [activity-reporting-service (maybe-get-service this :ActivityReportingService)]
                            (fn [& payload]
                              (try
                                  (activity-proto/report-activity! activity-reporting-service (first payload))
                                (catch Exception e
                                  (log/error
                                    (i18n/trs "Reporting CA event failed with: {0}\nPayload: {1}"
                                            (.getMessage e)
                                            (first payload))))))
                            (constantly nil))]
      (ca-gen/initialize! settings)
      (log/info (i18n/trs "CA Service adding a ring handler"))
      (add-ring-handler
        this
        (core/get-wrapped-handler
          (-> (core/web-routes settings report-activity)
              ((partial comidi/context path))
              comidi/routes->handler)
          settings
          path
          auth-handler
          puppet-version)
        {:normalize-request-uri true})
      (when (not= ca-crl-file host-crl-file)
        (watch-protocol/add-watch-dir! watcher
                                       (fs/parent ca-crl-file))
        (watch-protocol/add-callback!
         watcher
         (fn [events]
           (when (some #(and (:changed-path %)
                             (= (.getCanonicalPath (:changed-path %))
                                ca-crl-file))
                       events)
             (ca/retrieve-ca-crl! ca-crl-file host-crl-file)))))
      (watch-protocol/add-watch-dir! watcher
                                     (fs/parent infra-nodes-file))
      (watch-protocol/add-callback!
       watcher
       (fn [events]
         (when (some #(and (:changed-path %)
                           (= (.getCanonicalPath (:changed-path %))
                              infra-nodes-file))
                     events)
           (ca/generate-infra-serials! settings))))
      (register-status
        "ca"
        (status-core/get-artifact-version "puppetlabs" "puppetserver")
        1
        core/v1-status)
      (assoc context :auth-handler auth-handler
                     :watcher watcher
                     :ca-settings settings)))
  (start [this context]
    (log/info (i18n/trs "Starting CA service"))
    (interspaced one-day-ms #(evaluate-crls-for-expiration (:ca-settings context)) ca-scheduled-job-group-id)
    context)

  (stop [this context]
    (log/info (i18n/trs "Stopping CA service"))
    (stop-jobs ca-scheduled-job-group-id)
    (dissoc context :ca-settings))

  (initialize-master-ssl!
   [this master-settings certname]
   (let [settings (:ca-settings (get-config))]
     (ca/initialize-master-ssl! master-settings certname settings)))

  (retrieve-ca-cert!
    [this localcacert]
    (ca/retrieve-ca-cert! (get-in-config [:puppetserver :cacert])
                          localcacert))

  (retrieve-ca-crl!
    [this localcacrl]
    (ca/retrieve-ca-crl! (get-in-config [:puppetserver :cacrl])
                         localcacrl))

  (get-auth-handler
    [this]
    (:auth-handler (tk-services/service-context this))))
