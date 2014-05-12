(ns puppetlabs.master.services.master.master-service
  (:require [clojure.tools.logging :as log]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.master.services.master.master-core :as core]
            [puppetlabs.master.certificate-authority :as ca]
            [me.raynes.fs :as fs]))

(defservice master-service
            [[:WebserverService add-ring-handler]
             [:JvmPuppetConfigService get-in-config]
             [:RequestHandlerService handle-request]]
            (init [this context]
              (let [path            ""
                    config            (get-in-config [:jvm-puppet])
                    master-certname   (get-in config [:certname])
                    ca-name           (ca/ca-name master-certname)
                    ca-file-paths     (select-keys config [:cacert
                                                           :cacrl
                                                           :cakey
                                                           :capub
                                                           :localcacert])
                    master-file-paths (select-keys config [:hostcert
                                                           :hostprivkey
                                                           :hostpubkey])]

                    ; TODO - https://tickets.puppetlabs.com/browse/PE-3929
                    ; The master needs to eventually get these files from the CA server
                    ; via http or git or something.
                    (ca/initialize!
                      ca-file-paths master-file-paths master-certname ca-name)

                    (log/info "Master Service adding a ring handler")
                    (add-ring-handler
                      (compojure/context path [] (core/compojure-app handle-request))
                      path))
                  context))
