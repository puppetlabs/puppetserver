(ns puppetlabs.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]))

(defservice master-service
  [[:WebserverService add-ring-handler]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]
   [:CaService initialize-master-ssl!]]
  (init
   [this context]
   (core/validate-memory-requirements!)
   (let [path     ""
         config   (get-config)
         certname (get-in config [:puppet-server :certname])
         settings (ca/config->master-settings config)]

     (initialize-master-ssl! settings certname)

     (log/info "Master Service adding a ring handler")
     (add-ring-handler
      (compojure/context path [] (core/compojure-app handle-request))
      path))
   context))
