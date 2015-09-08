(ns puppetlabs.services.puppet-admin.puppet-admin-service
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.services.puppet-admin.puppet-admin-core :as core]
            [clojure.tools.logging :as log]))

(defservice puppet-admin-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:JRubyPuppetService]
   [:AuthorizationService wrap-with-authorization-check]]
  (init
    [this context]
    (log/info "Starting Puppet Admin web app")
    (let [route (get-route this)
          settings (core/config->puppet-admin-settings (get-config))
          jruby-service (services/get-service this :JRubyPuppetService)
          whitelist-settings (core/puppet-admin-settings->whitelist-settings
                               settings)]
      (if (not-empty whitelist-settings)
        (log/warn
          (str "The 'client-whitelist' and 'authorization-required' settings in "
               "the 'puppet-admin' section are deprecated and will be removed "
               "in a future release.  Remove these settings and instead create an "
               "appropriate authorization rule in the "
               "/etc/puppetlabs/puppetserver/conf.d file.")))
      (add-ring-handler
        this
        (core/build-ring-handler route
                                 whitelist-settings
                                 jruby-service
                                 wrap-with-authorization-check)))
    context))
