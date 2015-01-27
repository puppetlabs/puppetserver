(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(defn- handle-request
  [request jruby-service config]
  (jruby/with-jruby-puppet jruby-puppet jruby-service
    (core/handle-request request jruby-puppet config)))

(tk/defservice request-handler-service
               handler/RequestHandlerService
               [[:PuppetServerConfigService get-config]]
               (handle-request
                 [this request]
                 (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
                       config (core/config->request-handler-settings (get-config))]
                   (handle-request request jruby-service config)))
               (handle-invalid-request
                 [this request]
                 (core/handle-invalid-request request)))
