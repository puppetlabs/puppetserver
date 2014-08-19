(ns puppetlabs.master.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.master.services.ca.certificate-authority-core :as core]
            [compojure.core :as compojure]
            [me.raynes.fs :as fs]))

(tk/defservice certificate-authority-service
  [[:PuppetServerConfigService get-config get-in-config]
   [:WebserverService add-ring-handler]]
  (init
   [this context]
   (let [path     ""
         settings (ca/config->ca-settings (get-config))
         puppet-version (get-in-config [:puppet-server :puppet-version])]
     (log/info "CA Service adding a ring handler")
     (add-ring-handler
      (compojure/context path [] (core/compojure-app settings puppet-version))
      path))
   context))
