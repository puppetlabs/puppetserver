(ns puppetlabs.services.legacy-routes.legacy-routes-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.services.legacy-routes.legacy-routes-core :as legacy-routes-core]
            [puppetlabs.services.ca.certificate-authority-core :as ca-core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.legacy-routes :as legacy-routes]
            [puppetlabs.services.master.master-service :as master-service]))

(tk/defservice legacy-routes-service
  [[:WebroutingService add-ring-handler get-route]
   [:RequestHandlerService handle-request]
   [:PuppetServerConfigService get-config]
   [:MasterService]
   [:CaService]]
  (init
    [this context]
    (let [path (get-route this)
          config (get-config)
          puppet-version (get-in config [:puppet-server :puppet-version])
          ca-settings (ca/config->ca-settings (get-config))
          ca-mount (get-route (tk-services/get-service this :CaService))
          master-mount (master-service/get-master-path config)
          master-handler (master-core/build-ring-handler handle-request)
          ca-handler (ca-core/build-ring-handler ca-settings puppet-version)
          ring-handler (legacy-routes-core/build-ring-handler
                         master-handler
                         master-mount
                         master-core/puppet-API-versions
                         ca-handler
                         ca-mount
                         master-core/puppet-ca-API-versions)]
      (add-ring-handler
        this
        (compojure/context path [] ring-handler)))
    context)
  (start
    [this context]
    (log/info (str "The legacy routing service has successfully "
                "started and is now ready to handle requests"))
    context))
