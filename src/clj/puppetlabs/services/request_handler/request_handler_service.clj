(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as request-handler-core]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice request-handler-service
  handler/RequestHandlerService
  [[:PuppetServerConfigService get-config]]
  (init [this context]
    (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
          config (request-handler-core/config->request-handler-settings (get-config))]
      (assoc context :request-handler (request-handler-core/build-request-handler jruby-service config))))
  (handle-request
    [this request]
    (let [handler (:request-handler (tk-services/service-context this))]
      (handler request))))
