(ns puppetlabs.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.ca.certificate-authority-core :as core]
            [puppetlabs.services.protocols.ca :refer [CaService]]
            [puppetlabs.comidi :as comidi]))

(tk/defservice certificate-authority-service
  CaService
  [[:PuppetServerConfigService get-config get-in-config]
   [:WebroutingService add-ring-handler get-route]
   [:AuthorizationService wrap-with-authorization-check]]
  (init
   [this context]
   (let [path           (get-route this)
         settings       (ca/config->ca-settings (get-config))
         puppet-version (get-in-config [:puppet-server :puppet-version])]
     (if (not-empty (:access-control settings))
       (log/warn
         (str "The 'client-whitelist' and 'authorization-required' settings in "
              "the 'certificate-authority.certificate-status' section are "
              "deprecated and will be removed in a future release.  Remove these "
              "settings and instead create an appropriate authorization rule in "
              "the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")))
     (ca/initialize! settings)
     (log/info "CA Service adding a ring handler")
     (add-ring-handler
       this
       (core/get-wrapped-handler
         (-> (core/web-routes settings)
             ((partial comidi/context path))
             comidi/routes->handler)
         settings
         path
         wrap-with-authorization-check
         puppet-version)))
   context)

  (initialize-master-ssl!
   [this master-settings certname]
   (let [settings (ca/config->ca-settings (get-config))]
     (ca/initialize-master-ssl! master-settings certname settings)))

  (retrieve-ca-cert!
    [this localcacert]
    (ca/retrieve-ca-cert! (get-in-config [:puppet-server :cacert])
                          localcacert))

  (retrieve-ca-crl!
    [this localcacrl]
    (ca/retrieve-ca-crl! (get-in-config [:puppet-server :cacrl])
                         localcacrl)))
