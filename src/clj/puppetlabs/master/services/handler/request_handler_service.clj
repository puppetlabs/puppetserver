(ns puppetlabs.master.services.handler.request-handler-service
  (:require [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.master.services.protocols.request-handler :as handler]
            [puppetlabs.master.services.handler.request-handler-core :as core]
            [puppetlabs.master.services.jruby.jruby-puppet-service :as jruby]))

(tk/defservice request-handler-service
               handler/RequestHandlerService
               [JRubyPuppetService]
               (handle-request
                 [this environment request]
                 (let [jruby-service (get-service :JRubyPuppetService)]
                   (jruby/with-jruby-puppet
                     jruby-puppet
                     jruby-service
                     {:environment (keyword environment)}
                     (core/handle-request request jruby-puppet)))))
