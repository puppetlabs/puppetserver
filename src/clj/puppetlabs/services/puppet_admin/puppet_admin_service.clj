(ns puppetlabs.services.puppet-admin.puppet-admin-service
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.puppet-admin.puppet-admin-core :as core]
            [clojure.tools.logging :as log]
            [compojure.core :as compojure]))

(defservice puppet-admin-service
  [[:ConfigService get-config]
   [:WebroutingService add-ring-handler get-route]]
  (init
    [this context]
    (log/info "Starting Puppet Admin web app")
    (let [settings (core/config->puppet-admin-settings (get-config))]
      (add-ring-handler
        this
        (core/build-ring-handler (get-route this) settings)))
    context))