(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice request-handler-service
               handler/RequestHandlerService
               [[:PuppetServerConfigService get-config]]
               (handle-request
                 [this request]
                 (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
                       config (core/config->request-handler-settings (get-config))]
                   (core/handle-request request jruby-service config))))
