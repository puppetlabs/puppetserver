(ns puppetlabs.services.legacy-routes.legacy-routes-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]
            [puppetlabs.services.legacy-routes.legacy-routes-core :as legacy-routes-core]
            [puppetlabs.services.ca.certificate-authority-core :as ca-core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice legacy-routes-service
  [[:WebroutingService add-ring-handler get-route]
   [:RequestHandlerService handle-request]
   [:PuppetServerConfigService get-config]
   [:AuthorizationService wrap-with-authorization-check]]
  (init
    [this context]
    (let [ca-service (tk-services/get-service this :CaService)
          path (get-route this)
          config (get-config)
          puppet-version (get-in config [:puppet-server :puppet-version])
          use-legacy-auth-conf (get-in config
                                       [:jruby-puppet :use-legacy-auth-conf]
                                       true)
          master-ns (keyword (tk-services/service-symbol
                               (tk-services/get-service this :MasterService)))
          master-route-config (master-core/get-master-route-config
                                master-ns
                                config)
          master-handler-info {:mount       (master-core/get-master-mount
                                              master-ns
                                              master-route-config)
                               :handler     (master-core/get-handler
                                              handle-request
                                              path
                                              wrap-with-authorization-check
                                              use-legacy-auth-conf
                                              puppet-version)
                               :api-version master-core/puppet-API-version}
          real-ca-service? (= (namespace (tk-services/service-symbol ca-service))
                              "puppetlabs.services.ca.certificate-authority-service")
          ca-handler-info (when
                            real-ca-service?
                            {:mount       (get-route ca-service)
                             :handler     (ca-core/get-handler
                                            (ca/config->ca-settings (get-config))
                                            path
                                            wrap-with-authorization-check
                                            puppet-version)
                             :api-version ca-core/puppet-ca-API-version})
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
