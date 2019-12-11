(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as request-handler-core]
            [puppetlabs.puppetserver.jruby-request :as jruby-request]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

(tk/defservice request-handler-service
  handler/RequestHandlerService
  [[:PuppetServerConfigService get-config]
   [:ConfigService get-in-config]
   [:VersionedCodeService current-code-id]
   [:JRubyPuppetService]
   [:JRubyMetricsService]]
  (init [this context]
    (let [config (get-config)
          max-queued-requests (get-in-config [:jruby-puppet :max-queued-requests] 0)
          max-retry-delay (get-in-config [:jruby-puppet :max-retry-delay] 1800)
          jruby-service (tk-services/get-service this :JRubyPuppetService)
          metrics-service (tk-services/get-service this :JRubyMetricsService)
          request-handler (request-handler-core/build-request-handler
                            jruby-service
                            (request-handler-core/config->request-handler-settings
                              config)
                            current-code-id)]
      (when (contains? (:master config) :allow-header-cert-info)
        (if (true? (get-in config [:jruby-puppet :use-legacy-auth-conf]))
          (log/warn (format "%s %s"
                            (i18n/trs "The ''master.allow-header-cert-info'' setting is deprecated.")
                            (i18n/trs "Remove it, set ''jruby-puppet.use-legacy-auth-conf'' to ''false'', migrate your authorization rule definitions in the /etc/puppetlabs/puppet/auth.conf file to the /etc/puppetlabs/puppetserver/conf.d/auth.conf file, and set ''authorization.allow-header-cert-info'' to the desired value.")))
          (log/warn (format "%s %s"
                            (i18n/trs "The ''master.allow-header-cert-info'' setting is deprecated and will be ignored in favor of the ''authorization.allow-header-cert-info'' setting because the ''jruby-puppet.use-legacy-auth-conf'' setting is ''false''.")
                            (i18n/trs "Remove the ''master.allow-header-cert-info'' setting.")))))
      (assoc context :request-handler (if (pos? max-queued-requests)
                                        (jruby-request/wrap-with-request-queue-limit
                                          request-handler
                                          metrics-service
                                          max-queued-requests
                                          max-retry-delay)
                                        request-handler))))

  (handle-request
    [this request]
    (let [handler (:request-handler (tk-services/service-context this))]
      (handler request))))
