(ns puppetlabs.services.request-handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.request-handler :as handler]
            [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(defn- environment->pool-descriptor
  [environment]
  {:environment (keyword environment)})

(defn- handle-request
  [pool-descriptor request jruby-service]
  (jruby/with-jruby-puppet jruby-puppet jruby-service pool-descriptor
    (core/handle-request request jruby-puppet)))

(tk/defservice request-handler-service
               handler/RequestHandlerService
               [[:JRubyPuppetService get-default-pool-descriptor]]
               (handle-request
                 [this request]
                 (let [pool-descriptor (if-let [environment (:environment request)]
                                         ;; TODO : This should later intelligently choose the pool
                                         ;; TODO : descriptor based up on the environment of the req.
                                         (get-default-pool-descriptor)
                                         #_(environment->pool-descriptor environment)
                                         (get-default-pool-descriptor))
                       jruby-service (tk-services/get-service this :JRubyPuppetService)]
                   (handle-request pool-descriptor request jruby-service)))

               (handle-request
                 [this environment request]
                 ;; TODO : This should later intelligently choose the pool
                 ;; TODO : descriptor based up on the environment of the req.
                 (let [pool-descriptor (get-default-pool-descriptor)
                       jruby-service (tk-services/get-service this :JRubyPuppetService)]
                   (handle-request pool-descriptor request jruby-service))))
