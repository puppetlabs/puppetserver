(ns puppetlabs.services.puppet-admin.puppet-admin-service
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.services.puppet-admin.puppet-admin-core :as core]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

(defservice puppet-admin-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]
   [:CaService get-auth-handler]]
  (init
    [this context]
    (log/info (i18n/trs "Starting Puppet Admin web app"))
    (let [route (get-route this)
          settings (core/config->puppet-admin-settings (get-config))
          jruby-service (services/get-service this :JRubyPuppetService)
          whitelist-settings (core/puppet-admin-settings->whitelist-settings
                               settings)
          client-whitelist (:client-whitelist whitelist-settings)]
      (cond
        (or (false? (:authorization-required whitelist-settings))
            (not-empty client-whitelist))
        (log/warn (format "%s %s"
                          (i18n/trs "The ''client-whitelist'' and ''authorization-required'' settings in the ''puppet-admin'' section are deprecated and will be removed in a future release.")
                          (i18n/trs "Remove these settings and create an appropriate authorization rule in the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")))
        (not (nil? client-whitelist))
        (log/warn (format "%s %s %s"
                          (i18n/trs "The ''client-whitelist'' and ''authorization-required'' settings in the ''puppet-admin'' section are deprecated and will be removed in a future release.")
                          (i18n/trs "Because the ''client-whitelist'' is empty and ''authorization-required'' is set to ''false'', the ''puppet-admin'' settings will be ignored and authorization for the ''puppet-admin'' endpoints will be done per the authorization rules in the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")
                          (i18n/trs "To suppress this warning, remove the ''puppet-admin'' configuration settings."))))
      (add-ring-handler
        this
        (core/build-ring-handler route
                                 whitelist-settings
                                 jruby-service
                                 (get-auth-handler))
        {:normalize-request-uri true}))
    context))
