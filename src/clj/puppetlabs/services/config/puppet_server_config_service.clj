(ns ^{:doc
       "Implementation of the PuppetServerConfigService."}
    puppetlabs.services.config.puppet-server-config-service

  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.puppet-server-config
             :refer [PuppetServerConfigService]]
            [puppetlabs.services.config.puppet-server-config-core :as core]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.i18n.core :as i18n]))

(tk/defservice puppet-server-config-service
  PuppetServerConfigService
  [[:ConfigService get-config get-in-config]
   [:WebserverService override-webserver-settings!]
   [:JRubyPuppetService]]

  (init
    [this context]
    (let [tk-config (get-config)]
      (core/validate-tk-config! tk-config)
      (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
            puppet-config (core/get-puppet-config jruby-service)]
        (log/debug (format "%s\n%s" (i18n/trs "Initializing with the following settings from core Puppet:") (ks/pprint-to-string puppet-config)))
        (core/init-webserver! override-webserver-settings!
                              (get-in tk-config
                                      [:webserver :puppet-server]
                                      (get-in tk-config [:webserver]))
                              puppet-config)
        (assoc context :puppet-config
                       {:puppetserver puppet-config}))))

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
