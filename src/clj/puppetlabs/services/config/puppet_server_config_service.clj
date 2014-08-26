(ns ^{:doc
       "Implementation of the PuppetServerConfigService."}
    puppetlabs.services.config.puppet-server-config-service

  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.puppet-server-config
             :refer [PuppetServerConfigService]]
            [puppetlabs.services.config.puppet-server-config-core :as core]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice puppet-server-config-service
  PuppetServerConfigService
  [[:ConfigService get-config get-in-config]
   [:JRubyPuppetService get-default-pool-descriptor]]

  (init
    [this context]
    (let [tk-config (get-config)]
      (core/validate-tk-config! tk-config))

    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          pool-descriptor (get-default-pool-descriptor)
          puppet-config (core/get-puppet-config
                          jruby-service
                          pool-descriptor)]
      (assoc context :puppet-config
                     {:puppet-server puppet-config})))

  (get-config
    [this]
    (let [context        (tk-services/service-context this)
          puppet-config  (:puppet-config context)]
      (merge puppet-config (get-config))))

  (get-in-config
    [this ks]
    (let [context        (tk-services/service-context this)
          puppet-config  (:puppet-config context)]
      (or
        (get-in puppet-config ks)
        (get-in-config ks))))

  (get-in-config
    [this ks default]
    (let [context        (tk-services/service-context this)
          puppet-config  (:puppet-config context)]
      (or
        (get-in puppet-config ks)
        (get-in-config ks default)))))
