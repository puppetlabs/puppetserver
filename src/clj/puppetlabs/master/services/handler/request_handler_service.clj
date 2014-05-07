(ns puppetlabs.master.services.handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.master.services.protocols.request-handler :as handler]
            [puppetlabs.master.services.handler.request-handler-core :as core]
            [puppetlabs.master.services.jruby.jruby-puppet-service :as jruby]))

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
                                         (environment->pool-descriptor environment)
                                         (get-default-pool-descriptor))
                       jruby-service (get-service :JRubyPuppetService)]
                   (handle-request pool-descriptor request jruby-service)))

               (handle-request
                 [this environment request]
                 (let [pool-descriptor (environment->pool-descriptor environment)
                       jruby-service (get-service :JRubyPuppetService)]
                   (handle-request pool-descriptor request jruby-service))))
