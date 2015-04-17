(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [clojure.tools.logging :as log]))

(defn- handle-request
  [request jruby-service config puppet-version]
  (try
    (jruby/with-jruby-puppet jruby-puppet jruby-service
                             (core/handle-request request jruby-puppet config))
    (catch IllegalStateException e
      (let [message (.getMessage e)]
        (log/error message)
        {:status  503
         :body    message
         :headers {"Content-Type"     "text/plain"
                   "X-Puppet-version" puppet-version}}))))

(tk/defservice request-handler-service
               handler/RequestHandlerService
               [[:PuppetServerConfigService get-config get-in-config]]
               (handle-request
                 [this request]
                 (let [jruby-service (tk-services/get-service this :JRubyPuppetService)
                       config (core/config->request-handler-settings (get-config))]
                   (core/handle-request request jruby-service config))))
