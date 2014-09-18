(ns puppetlabs.services.version.version-check-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.version.version-check-core :as version-check]))

(tk/defservice version-check-service
  [[:ConfigService get-in-config]]
  (init
    [this context]
    (let [product-name (get-in-config [:product :name])
          update-server-url (get-in-config [:product :update-server-url])]
      (version-check/validate-config! product-name update-server-url)
      (future
        (try
          (version-check/check-for-updates product-name update-server-url)
          (catch Exception e
            (log/warn e "Error occurred while checking for updates")
            (throw e)))))
    context))


