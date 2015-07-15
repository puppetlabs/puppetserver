(ns puppetlabs.services.puppet-admin.puppet-admin-service
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.services.puppet-admin.puppet-admin-core :as core]
            [clojure.tools.logging :as log]))

(defservice puppet-admin-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]
   [:JRubyPuppetService]]
  (init
    [this context]
    (log/info "Starting Puppet Admin web app")
    (let [route (get-route this)
          settings (core/config->puppet-admin-settings (get-config))
          jruby-service (services/get-service this :JRubyPuppetService)]
      (add-ring-handler
        this
        (core/build-ring-handler route settings jruby-service)))
    context))
