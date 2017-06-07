(ns puppetlabs.services.jruby.puppet-environments
  (:import (com.puppetlabs.puppetserver EnvironmentRegistry))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

(defprotocol EnvironmentStateContainer
  (environment-state [this])
  (mark-all-environments-expired! [this])
  (mark-environment-expired! [this env-name]))

(defn environment-registry
  []
  (let [state             (atom {})
        mark-expired!     (fn [acc env-name]
                            (assoc-in acc [env-name :expired] true))
        mark-all-expired! (fn [m]
                            (reduce mark-expired! m (keys m)))]
    (reify
      EnvironmentRegistry
      (registerEnvironment [this env-name]
        (when-not env-name
          (throw (IllegalArgumentException. (i18n/trs "Missing environment name!"))))
        (log/debug (i18n/trs "Registering environment ''{0}''" env-name))
        (swap! state assoc-in [(keyword env-name) :expired] false)
        nil)
      (isExpired [this env-name]
        (when-not env-name
          (throw (IllegalArgumentException. (i18n/trs "Missing environment name!"))))
        (get-in @state [(keyword env-name) :expired] true))
      (removeEnvironment [this env-name]
        (when-not env-name
          (throw (IllegalArgumentException. (i18n/trs "Missing environment name!"))))
        (log/debug (i18n/trs "Removing environment ''{0}'' from registry" env-name))
        (swap! state dissoc (keyword env-name))
        nil)

      EnvironmentStateContainer
      (environment-state [this] state)
      (mark-all-environments-expired! [this]
        (log/info (i18n/trs "Marking all registered environments as expired."))
        (swap! state mark-all-expired!))
      (mark-environment-expired! [this env-name]
        (log/info (i18n/trs "Marking environment ''{0}'' as expired." env-name))
        (swap! state mark-expired! (keyword env-name))))))
