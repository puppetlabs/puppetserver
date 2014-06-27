(ns puppetlabs.master.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.master.services.master.master-core :as core]
            [puppetlabs.master.certificate-authority :as ca]))

(defservice master-service
            [[:WebserverService add-ring-handler]
             [:JvmPuppetConfigService get-in-config]
             [:RequestHandlerService handle-request]]
            (init [this context]
              (let [path              ""
                    config            (get-in-config [:jvm-puppet])
                    master-certname   (get-in config [:certname])
                    ca-settings       (assoc
                                        (select-keys config (keys ca/CaSettings))
                                        :load-path (get-in-config
                                                    [:jruby-puppet :load-path]))
                    master-file-paths (select-keys
                                        config (keys ca/MasterFilePaths))]

                    ; TODO - https://tickets.puppetlabs.com/browse/PE-3929
                    ; The master needs to eventually get these files from the CA server
                    ; via http or git or something.
                    (ca/initialize! ca-settings master-file-paths master-certname)

                    (log/info "Master Service adding a ring handler")
                    (add-ring-handler
                      (compojure/context path [] (core/compojure-app handle-request))
                      path))
              context))
