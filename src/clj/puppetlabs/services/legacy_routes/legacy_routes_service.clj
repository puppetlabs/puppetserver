(ns puppetlabs.services.legacy-routes.legacy-routes-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as comidi]
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
   [:PuppetServerConfigService get-config]]
  (init
    [this context]
    (let [ca-service (tk-services/get-service this :CaService)
          path (get-route this)
          config (get-config)
          puppet-version (get-in config [:puppet-server :puppet-version])
          master-ns (keyword (tk-services/service-symbol
                               (tk-services/get-service this :MasterService)))
          master-route-config (master-core/get-master-route-config
                                master-ns
                                config)
          master-handler-info {:mount (master-core/get-master-mount
                                        master-ns
                                        master-route-config)
                               :handler (-> (master-core/root-routes
                                              handle-request)
                                            (#(comidi/context path %))
                                            comidi/routes->handler
                                            (master-core/wrap-middleware
                                              puppet-version))
                               :api-version master-core/puppet-API-versions}
          real-ca-service? (= (namespace (tk-services/service-symbol ca-service))
                              "puppetlabs.services.ca.certificate-authority-service")
          ca-handler-info (when
                            real-ca-service?
                            {:mount (get-route ca-service)
                             :handler (-> (ca-core/web-routes
                                            (ca/config->ca-settings (get-config)))
                                          (#(comidi/context path %))
                                          comidi/routes->handler
                                          (ca-core/wrap-middleware puppet-version))
                             :api-version master-core/puppet-ca-API-versions})
          ring-handler (legacy-routes-core/build-ring-handler
                         master-handler-info
                         ca-handler-info)]
      (add-ring-handler this ring-handler))
    context)
  (start
    [this context]
    (log/info (str "The legacy routing service has successfully "
                "started and is now ready to handle requests"))
    context))
