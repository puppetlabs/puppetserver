(ns puppetlabs.services.config.certificate-authority-config-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.certificate-authority-config
             :refer [CertificateAuthorityConfigService]]
            [puppetlabs.services.config.certificate-authority-config-core :as core]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice certificate-authority-config-service
  CertificateAuthorityConfigService
  [[:PuppetServerConfigService get-config get-in-config]]

  (init
    [this context]
    (let [settings (core/config->ca-settings (get-config))
          custom-oid-file (get-in-config [:puppetserver :trusted-oid-mapping-file])
          oid-mappings (core/get-oid-mappings custom-oid-file)]

      (core/validate-settings! settings)
      (assoc context
             :ca-settings
             (assoc settings :oid-mappings oid-mappings))))

  (get-config
    [this]
    (let [context     (tk-services/service-context this)
          ca-settings (:ca-settings context)]
      (assoc (get-config) :ca-settings ca-settings)))

  (get-in-config
    [this ks]
    (let [context      (tk-services/service-context this)
          ca-settings (:ca-settings context)]
      (or
        (get-in ca-settings ks)
        (get-in-config ks))))

  (get-in-config
    [this ks default]
    (let [context     (tk-services/service-context this)
          ca-settings (:ca-settings context)]
      (or
        (get-in ca-settings ks)
        (get-in-config ks default)))))
