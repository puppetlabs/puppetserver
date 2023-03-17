(ns puppetlabs.services.analytics.analytics-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.services.analytics.dropsonde :refer [run-dropsonde]]))

(defprotocol AnalyticsService
  "Protocol placeholder for the analytics service.")

(defservice analytics-service
  AnalyticsService
  [[:PuppetServerConfigService get-config]
   [:SchedulerService interspaced]]

  (start
   [this context]
   (let [config (get-config)]
     ;; Configure analytics
     (let [product-name (or (get-in config [:product :name])
                            {:group-id "puppetlabs"
                             :artifact-id "puppetserver"})
           checkin-interval-millis (* 1000 60 60 24) ; once per day
           update-server-url (get-in config [:product :update-server-url])
           check-for-updates (get-in config [:product :check-for-updates] true)]
       (if check-for-updates
         (interspaced checkin-interval-millis
                      (fn []
                       (try
                         (version-check/check-for-update
                          {:product-name product-name} update-server-url)
                         (catch Exception _
                           (log/error (i18n/trs "Failed to check for product updates"))))))
         (log/info (i18n/trs "Not checking for updates - opt-out setting exists"))))
     (log/info (i18n/trs "Puppet Server Update Service has successfully started and will run in the background"))

     ;; Configure dropsonde, enabled by default if not specified
     (let [dropsonde-enabled (get-in config [:dropsonde :enabled] true)
           ;; once a week, config value is documented as seconds
           dropsonde-interval-millis (* 1000 (get-in config [:dropsonde :interval]
                                               (* 60 60 24 7)))]
       (if dropsonde-enabled
         (interspaced dropsonde-interval-millis #(run-dropsonde config))
         (log/info (i18n/trs (str "Not submitting module metrics via Dropsonde -- submission is disabled. "
                                  "Enable this feature by setting `dropsonde.enabled` to true in Puppet Server''s config."))))))
   context))
