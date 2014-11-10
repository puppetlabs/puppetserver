(ns puppetlabs.services.jruby.puppet-environments
  (:import (com.puppetlabs.puppetserver EnvironmentRegistry))
  (:require [clojure.tools.logging :as log]))

(defprotocol EnvironmentStateContainer
  (environment-state [this])
  (mark-all-environments-stale [this]))

(defn environment-registry
  []
  (let [state          (atom {})
        mark-stale     (fn [acc env-name]
                         (assoc-in acc [env-name :stale] true))
        mark-all-stale (fn [m]
                         (reduce mark-stale m (keys m)))]
    (reify
      EnvironmentRegistry
      (registerEnvironment [this env-name]
        (log/debugf "Registering environment '%s'" env-name)
        (swap! state assoc-in [(keyword env-name) :stale] false)
        nil)
      (isExpired [this env-name]
        (get-in @state [(keyword env-name) :stale]))
      (removeEnvironment [this env-name]
        (log/debugf "Removing environment '%s' from registry" env-name)
        (swap! state dissoc (keyword env-name))
        nil)

      EnvironmentStateContainer
      (environment-state [this] state)
      (mark-all-environments-stale [this]
        (log/debug "Marking all registered environments stale.")
        (swap! state mark-all-stale)))))
