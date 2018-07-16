(ns puppetlabs.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.protocols.filesystem-watch-service :as watch-protocol]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.ca.certificate-authority-core :as core]
            [puppetlabs.services.protocols.ca :refer [CaService]]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.i18n.core :as i18n]))

(tk/defservice certificate-authority-service
  CaService
  [[:PuppetServerConfigService get-config get-in-config]
   [:WebroutingService add-ring-handler get-route]
   [:AuthorizationService wrap-with-authorization-check]
   [:FilesystemWatchService create-watcher]]
  (init
    [this context]
    (let [path (get-route this)
          settings (ca/config->ca-settings (get-config))
          puppet-version (get-in-config [:puppetserver :puppet-version])
          custom-oid-file (get-in-config [:puppetserver :trusted-oid-mapping-file])
          oid-mappings (ca/get-oid-mappings custom-oid-file)
          auth-handler (fn [request] (wrap-with-authorization-check request {:oid-map oid-mappings}))
          ca-crl-file (.getCanonicalPath (fs/file
                                           (:cacrl settings)))
          host-crl-file (.getCanonicalPath (fs/file
                                       (get-in-config [:puppetserver :hostcrl])))
          watcher (create-watcher {:recursive false})]
      (ca/validate-settings! settings)
      (ca/initialize! settings)
      (log/info (i18n/trs "CA Service adding a ring handler"))
      (add-ring-handler
        this
        (core/get-wrapped-handler
          (-> (core/web-routes settings)
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
      (assoc context :auth-handler auth-handler
                     :watcher watcher
                     :ca-settings settings)))

  (initialize-master-ssl!
    [this master-settings certname]
    (let [settings (tk-services/service-context this)]
       (ca/initialize-master-ssl! master-settings certname (:ca-settings settings))))

  (retrieve-ca-cert!
    [this localcacert]
    (let [cacert (-> this tk-services/service-context :ca-settings :cacert)]
       (ca/retrieve-ca-cert! cacert localcacert)))

  (retrieve-ca-crl!
    [this localcacrl]
    (let  [cacrl (-> this tk-services/service-context :ca-settings :cacrl)]
       (ca/retrieve-ca-crl! cacrl localcacrl)))

  (get-auth-handler
    [this]
    (:auth-handler (tk-services/service-context this))))
