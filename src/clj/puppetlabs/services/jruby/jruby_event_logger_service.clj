(ns puppetlabs.services.jruby.jruby-event-logger-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.protocols.jruby-event-handler :as jruby-event-protocol]
            [clojure.tools.logging :as log]))

(trapperkeeper/defservice jruby-event-logger-service
  jruby-event-protocol/JRubyEventHandlerService
  []
  (instance-requested [this action]
    (log/trace "JRuby instance requested for action:" action))
  (instance-borrowed [this action instance]
    (log/trace "JRuby instance" (:id instance) "borrowed for action:" action))
  (instance-returned [this instance]
    (log/trace "JRuby instance" (:id instance) "returned.")))
