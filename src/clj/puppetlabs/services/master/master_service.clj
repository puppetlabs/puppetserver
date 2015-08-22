(ns puppetlabs.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.services.protocols.master :as master]))

(defservice master-service
  master/MasterService
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]
   [:CaService initialize-master-ssl! retrieve-ca-cert! retrieve-ca-crl!]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]]
  (init
   [this context]
   (core/validate-memory-requirements!)
   (let [config (get-config)
         certname (get-in config [:puppet-server :certname])
         localcacert (get-in config [:puppet-server :localcacert])
         puppet-version (get-in config [:puppet-server :puppet-version])
         hostcrl (get-in config [:puppet-server :hostcrl])
         settings (ca/config->master-settings config)
         product-name (or (get-in config [:product :name])
                          {:group-id    "puppetlabs"
                           :artifact-id "puppetserver"})
         update-server-url (get-in config [:product :update-server-url])
         use-legacy-auth-conf (get-in config
                                      [:jruby-puppet :use-legacy-auth-conf]
                                      true)]
     (version-check/check-for-updates! {:product-name product-name} update-server-url)

     (retrieve-ca-cert! localcacert)
     (retrieve-ca-crl! hostcrl)
     (initialize-master-ssl! settings certname)

     (log/info "Master Service adding ring handlers")
     (let [route-config (core/get-master-route-config ::master-service config)
           path (core/get-master-mount ::master-service route-config)
           ring-handler (when path
                          (core/get-handler
                            handle-request
                            path
                            wrap-with-authorization-check
                            use-legacy-auth-conf
                            puppet-version))]
       (if (map? route-config)
         (add-ring-handler this ring-handler
                           {:route-id :master-routes})
         (add-ring-handler this ring-handler))))
   context)
  (start
    [this context]
    (log/info "Puppet Server has successfully started and is now ready to handle requests")
    context))
