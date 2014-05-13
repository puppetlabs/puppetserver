(ns puppetlabs.master.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.master.services.ca.certificate-authority-core :as core]
            [compojure.core :as compojure]
            [me.raynes.fs :as fs]))

(tk/defservice certificate-authority-service
  [[:JvmPuppetConfigService get-in-config]
   [:WebserverService add-ring-handler]]
  (init
    [this context]
    (let [path            ""
          config          (get-in-config [:jvm-puppet])
          ca-settings     (select-keys config [:cacert
                                               :cacrl
                                               :cakey
                                               :ca-name
                                               :certdir
                                               :csrdir])]

      (log/info "CA Service adding a ring handler")
      (add-ring-handler
        (compojure/context path [] (core/compojure-app ca-settings))
        path))

    context))

