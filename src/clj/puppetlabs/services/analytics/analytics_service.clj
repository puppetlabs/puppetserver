(ns puppetlabs.services.analytics.analytics-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.dujour.version-check :as version-check]))

(defprotocol AnalyticsService
  "Protocol placeholder for the analytics service.")

(defservice analytics-service
  AnalyticsService
  [[:PuppetServerConfigService get-config]
   [:SchedulerService interspaced]]

  (start
   [this context]
   (let [config (get-config)
         product-name (or (get-in config [:product :name])
                          {:group-id "puppetlabs"
                           :artifact-id "puppetserver"})
         checkin-interval-millis (* 1000 60 60 24) ; once per day
         update-server-url (get-in config [:product :update-server-url])
         check-for-updates (get-in config [:product :check-for-updates] true)]
     (if check-for-updates
       (interspaced checkin-interval-millis
                    (fn [] (version-check/check-for-updates!
                            {:product-name product-name} update-server-url)))
       (log/info (i18n/trs "Not checking for updates - opt-out setting exists"))))
   (log/info (i18n/trs "Puppet Server Update Service has successfully started and will run in the background"))
   context))
