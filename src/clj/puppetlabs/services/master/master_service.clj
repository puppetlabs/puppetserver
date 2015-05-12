(ns puppetlabs.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.services.protocols.master :as master]))

(defn get-master-route-config
  [config]
  (get-in config [:web-router-service ::master-service]))

(defn get-master-path
  [config]
  (let [config-route (get-master-route-config config)]
    (cond
      (map? config-route)
      (:master-routes config-route)

      (string? config-route)
      config-route

      :else
      nil)))

(defservice master-service
  master/MasterService
  [[:WebroutingService add-ring-handler get-route get-registered-endpoints]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]
   [:CaService initialize-master-ssl! retrieve-ca-cert! retrieve-ca-crl!]
   [:JRubyPuppetService]]
  (init
   [this context]
   (core/validate-memory-requirements!)
   (let [config (get-config)
         certname          (get-in config [:puppet-server :certname])
         localcacert       (get-in config [:puppet-server :localcacert])
         hostcrl           (get-in config [:puppet-server :hostcrl])
         settings          (ca/config->master-settings config)
         jruby-service     (tk-services/get-service this :JRubyPuppetService)
         product-name      (or (get-in config [:product :name])
                               {:group-id    "puppetlabs"
                                :artifact-id "puppetserver"})
         upgrade-error     (core/construct-404-error-message jruby-service product-name)
         update-server-url (get-in config [:product :update-server-url])]
     (version-check/check-for-updates! {:product-name product-name} update-server-url)

     (retrieve-ca-cert! localcacert)
     (retrieve-ca-crl! hostcrl)
     (initialize-master-ssl! settings certname)

     (log/info "Master Service adding ring handlers")
     (let [path (get-master-path config)
           route-config (get-master-route-config config)
           ring-handler (core/build-ring-handler handle-request)
           route-handler (compojure/context path [] ring-handler)]
       (when (nil? path)
         (throw
           (IllegalArgumentException.
             "Could not find a properly configured route for the master service")))
       (cond
         (map? route-config)
         (add-ring-handler this route-handler {:route-id :master-routes})

         (string? route-config)
         (add-ring-handler this route-handler))))
   context)
  (start
    [this context]
    (log/info "Puppet Server has successfully started and is now ready to handle requests")
    context))
