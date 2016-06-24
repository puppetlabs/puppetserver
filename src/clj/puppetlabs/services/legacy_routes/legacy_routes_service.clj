(ns puppetlabs.services.legacy-routes.legacy-routes-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.services.legacy-routes.legacy-routes-core :as legacy-routes-core]
            [puppetlabs.services.ca.certificate-authority-core :as ca-core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.i18n.core :as i18n :refer [trs]]))

(tk/defservice legacy-routes-service
  [[:WebroutingService add-ring-handler get-route]
   [:RequestHandlerService handle-request]
   [:PuppetServerConfigService get-config]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]
   [:CaService get-auth-handler]]
  (init
    [this context]
    (let [ca-service (tk-services/get-service this :CaService)
          path (get-route this)
          config (get-config)
          puppet-version (get-in config [:puppetserver :puppet-version])
          use-legacy-auth-conf (get-in config
                                       [:jruby-puppet :use-legacy-auth-conf]
                                       true)
          master-ns (keyword (tk-services/service-symbol
                               (tk-services/get-service this :MasterService)))
          master-route-config (master-core/get-master-route-config
                                master-ns
                                config)
          jruby-service (tk-services/get-service this :JRubyPuppetService)
          master-route-handler (-> (master-core/root-routes handle-request
                                                            (partial identity)
                                                            jruby-service
                                                            (constantly nil)
                                                            false)
                                   ((partial comidi/context path))
                                   comidi/routes->handler)
          master-handler-info {:mount       (master-core/get-master-mount
                                              master-ns
                                              master-route-config)
                               :handler     (master-core/get-wrapped-handler
                                              master-route-handler
                                              (get-auth-handler)
                                              puppet-version
                                              use-legacy-auth-conf)
                               :api-version master-core/puppet-API-version}
          real-ca-service? (= (namespace (tk-services/service-symbol ca-service))
                              "puppetlabs.services.ca.certificate-authority-service")
          ca-settings (ca/config->ca-settings (get-config))
          ca-route-handler (-> ca-settings
                               (ca-core/web-routes)
                               ((partial comidi/context path))
                               comidi/routes->handler)
          ca-handler-info (when
                            real-ca-service?
                            (let [ca-mount (get-route ca-service)]
                              {:mount       ca-mount
                               :handler     (ca-core/get-wrapped-handler
                                              ca-route-handler
                                              ca-settings
                                              ca-mount
                                              (get-auth-handler)
                                              puppet-version)
                               :api-version ca-core/puppet-ca-API-version}))
          ring-handler (legacy-routes-core/build-ring-handler
                         master-handler-info
                         ca-handler-info)]
      (add-ring-handler this
                        ring-handler
                        {:normalize-request-uri true}))
    context)
  (start
    [this context]
    (log/info (trs "The legacy routing service has successfully started and is now ready to handle requests"))
    context))
