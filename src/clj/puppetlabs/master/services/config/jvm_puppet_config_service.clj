(ns ^{:doc
       "Implementation of the JvmPuppetConfigService."}
    puppetlabs.master.services.config.jvm-puppet-config-service

  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.master.services.protocols.jvm-puppet-config
             :refer [JvmPuppetConfigService]]
            [puppetlabs.master.services.config.jvm-puppet-config-core :as core]))

(tk/defservice jvm-puppet-config-service
               JvmPuppetConfigService
               [[:ConfigService get-config get-in-config]
                [:JRubyPuppetService get-default-pool-descriptor]
                [:WebserverService override-webserver-settings!]]

               (init
                 [this context]
                 (let [tk-config (get-config)]
                   (core/validate-tk-config! tk-config))

                 (let [jruby-service (get-service :JRubyPuppetService)
                       pool-descriptor (get-default-pool-descriptor)
                       puppet-config (core/get-puppet-config
                                       jruby-service
                                       pool-descriptor)]
                   (core/init-webserver! override-webserver-settings!
                                         puppet-config)
                   (assoc context :puppet-config
                                  {:jvm-puppet puppet-config})))

               (get-config
                 [this]
                 (let [context        (service-context this)
                       puppet-config  (:puppet-config context)]
                   (merge puppet-config (get-config))))

               (get-in-config
                 [this ks]
                 (let [context        (service-context this)
                       puppet-config  (:puppet-config context)]
                   (or
                     (get-in puppet-config ks)
                     (get-in-config ks))))

               (get-in-config
                 [this ks default]
                 (let [context        (service-context this)
                       puppet-config  (:puppet-config context)]
                   (or
                     (get-in puppet-config ks)
                     (get-in-config ks default)))))
