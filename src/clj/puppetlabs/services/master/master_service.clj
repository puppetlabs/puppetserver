(ns puppetlabs.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.services.protocols.master :as master]
            [puppetlabs.i18n.core :as i18n :refer [trs]]))

(defservice master-service
  master/MasterService
  [[:WebroutingService add-ring-handler get-route]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]
   [:CaService initialize-master-ssl! retrieve-ca-cert! retrieve-ca-crl! get-auth-handler]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]
   [:SchedulerService interspaced]
   [:VersionedCodeService get-code-content]]
  (init
   [this context]
   (core/validate-memory-requirements!)
   (let [config (get-config)
         certname (get-in config [:puppetserver :certname])
         localcacert (get-in config [:puppetserver :localcacert])
         puppet-version (get-in config [:puppetserver :puppet-version])
         hostcrl (get-in config [:puppetserver :hostcrl])
         settings (ca/config->master-settings config)
         product-name (or (get-in config [:product :name])
                          {:group-id    "puppetlabs"
                           :artifact-id "puppetserver"})
         checkin-interval-millis (* 1000 60 60 24) ; once per day
         update-server-url (get-in config [:product :update-server-url])
         use-legacy-auth-conf (get-in config
                                      [:jruby-puppet :use-legacy-auth-conf]
                                      true)
         jruby-service (tk-services/get-service this :JRubyPuppetService)
         environment-class-cache-enabled (get-in config
                                                 [:jruby-puppet
                                                  :environment-class-cache-enabled]
                                                 false)]
     (interspaced checkin-interval-millis
                  (fn [] (version-check/check-for-updates! {:product-name product-name} update-server-url)))

     (retrieve-ca-cert! localcacert)
     (retrieve-ca-crl! hostcrl)
     (initialize-master-ssl! settings certname)

     (log/info (trs "Master Service adding ring handlers"))
     (let [route-config (core/get-master-route-config ::master-service config)
           path (core/get-master-mount ::master-service route-config)
           ring-handler (when path
                          (-> (core/construct-root-routes puppet-version
                                                          use-legacy-auth-conf
                                                          jruby-service
                                                          get-code-content
                                                          handle-request
                                                          (get-auth-handler)
                                                          environment-class-cache-enabled)
                              ((partial comidi/context path))
                              comidi/routes->handler))]
       ;; if the webrouting config uses the old-style config where
       ;; there is a single key with a route-id, we need to deal with that
       ;; for backward compat.  We have a hard-coded assumption that this route-id
       ;; must be `master-routes`.  In Puppet Server 2.0, we also supported a
       ;; key called `invalid-in-puppet-4` in the same route config, even though
       ;; that key is no longer used for Puppet Server 2.1 and later.  We
       ;; should be able to remove this hack as soon as we are able to get rid
       ;; of the legacy routes.
       (if (and (map? route-config)
                (contains? route-config :master-routes))
         (add-ring-handler this
                           ring-handler
                           {:route-id :master-routes
                            :normalize-request-uri true})
         (add-ring-handler this
                           ring-handler
                           {:normalize-request-uri true}))))
   context)
  (start
    [this context]
    (log/info (trs "Puppet Server has successfully started and is now ready to handle requests"))
    context))
