(ns puppetlabs.services.legacy-routes.legacy-routes-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.services.legacy-routes.legacy-routes-core :as legacy-routes-core]
            [puppetlabs.services.ca.certificate-authority-core :as ca-core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.master.master-core :as master-core]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.metrics.http :as http-metrics]))

(tk/defservice legacy-routes-service
  [[:WebroutingService add-ring-handler get-route]
   [:RequestHandlerService handle-request]
   [:PuppetServerConfigService get-config]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]
   [:CaService get-auth-handler]
   [:MasterService]]
  (init
    [this context]
    (let [ca-service (tk-services/get-service this :CaService)
          path (get-route this)
          config (get-config)
          puppet-version (get-in config [:puppetserver :puppet-version])
          use-legacy-auth-conf (get-in config
                                       [:jruby-puppet :use-legacy-auth-conf]
                                       false)
          master-service (tk-services/get-service this :MasterService)
          master-ns (keyword (tk-services/service-symbol master-service))
          master-metrics (-> master-service
                             tk-services/service-context
                             :http-metrics)
          master-route-config (master-core/get-master-route-config
                                master-ns
                                config)
          jruby-service (tk-services/get-service this :JRubyPuppetService)
          master-routes (comidi/context path
                                        (master-core/root-routes handle-request
                                                                 (partial identity)
                                                                 jruby-service
                                                                 (fn [_ _ _]
                                                                   (throw (IllegalStateException.
                                                                            (i18n/trs "Versioned code not supported."))))
                                                                 (constantly nil)
                                                                 false))
          master-route-handler (comidi/routes->handler master-routes)
          master-mount (master-core/get-master-mount
                        master-ns
                        master-route-config)
          master-handler-info {:mount master-mount
                               :handler (-> (master-core/get-wrapped-handler
                                             master-route-handler
                                             (get-auth-handler)
                                             puppet-version
                                             use-legacy-auth-conf)
                                            (http-metrics/wrap-with-request-metrics
                                             master-metrics)
                                            (legacy-routes-core/add-root-path-to-route-id
                                             master-mount)
                                            (comidi/wrap-with-route-metadata
                                             master-routes))
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
    (log/info (i18n/trs "The legacy routing service has successfully started and is now ready to handle requests"))
    context))
