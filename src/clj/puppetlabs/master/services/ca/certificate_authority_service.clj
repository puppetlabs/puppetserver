(ns puppetlabs.master.services.ca.certificate-authority-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.master.services.ca.certificate-authority-core :as core]
            [compojure.core :as compojure]
            [schema.core :as schema]
            [me.raynes.fs :as fs]))

(def CaSettings
  { :cacert   String
    :cacrl    String
    :cakey    String
    :ca-name  String
    :ca-ttl   schema/Int
    :certdir  String
    :csrdir   String })

(tk/defservice certificate-authority-service
  [[:JvmPuppetConfigService get-in-config]
   [:WebserverService add-ring-handler]]
  (init
    [this context]
    (let [path            ""
          config          (get-in-config [:jvm-puppet])
          ca-settings     (select-keys config (keys CaSettings))]

      (schema/validate CaSettings ca-settings)

      (log/info "CA Service adding a ring handler")
      (add-ring-handler
        (compojure/context path [] (core/compojure-app ca-settings))
        path))

    context))

