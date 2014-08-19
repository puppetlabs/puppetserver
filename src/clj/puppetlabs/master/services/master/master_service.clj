(ns puppetlabs.master.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.master.services.master.master-core :as core]
            [puppetlabs.master.certificate-authority :as ca]))

(defservice master-service
  [[:WebserverService add-ring-handler]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]]
  (init
   [this context]
   (let [path            ""
         config          (get-config)
         master-certname (get-in config [:puppet-server :certname])
         master-settings (ca/config->master-settings config)
         ca-settings     (ca/config->ca-settings config)]

     ; TODO - https://tickets.puppetlabs.com/browse/PE-3929
     ; The master needs to eventually get these files from the CA server
     ; via http or git or something.
     (ca/initialize! ca-settings master-settings master-certname)

     (log/info "Master Service adding a ring handler")
     (add-ring-handler
      (compojure/context path [] (core/compojure-app handle-request))
      path))
   context))
