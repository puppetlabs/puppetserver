(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as request-handler-core]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n :refer [trs]]))

(tk/defservice request-handler-service
  handler/RequestHandlerService
  [[:PuppetServerConfigService get-config]
   [:VersionedCodeService current-code-id]
   [:JRubyPuppetService]]
  (init [this context]
    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          config (get-config)]
      (when (contains? (:master config) :allow-header-cert-info)
        (if (true? (get-in config [:jruby-puppet :use-legacy-auth-conf]))
          (log/warn (format "%s %s"
                            (trs "The ''master.allow-header-cert-info'' setting is deprecated.")
                            (trs "Remove it, set ''jruby-puppet.use-legacy-auth-conf'' to ''false'', migrate your authorization rule definitions in the /etc/puppetlabs/puppet/auth.conf file to the /etc/puppetlabs/puppetserver/conf.d/auth.conf file, and set ''authorization.allow-header-cert-info'' to the desired value.")))
          (log/warn (format "%s %s"
                            (trs "The ''master.allow-header-cert-info'' setting is deprecated and will be ignored in favor of the ''authorization.allow-header-cert-info'' setting because the ''jruby-puppet.use-legacy-auth-conf'' setting is ''false''.")
                            (trs "Remove the ''master.allow-header-cert-info'' setting.")))))
      (assoc context :request-handler
                     (request-handler-core/build-request-handler
                      jruby-service
                      (request-handler-core/config->request-handler-settings
                       config)
                      current-code-id))))
  (handle-request
    [this request]
    (let [handler (:request-handler (tk-services/service-context this))]
      (handler request))))
