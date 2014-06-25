(ns puppetlabs.master.services.puppet-profiler.puppet-profiler-core
  (:import (com.puppetlabs.master PuppetProfiler))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def Profiler (schema/maybe PuppetProfiler))
(def ProfilerConfig
  (schema/maybe
    {(schema/optional-key :enabled) (schema/either schema/Bool schema/Str)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn logging-profiler
  "A simple profiler implementation that times a block of code and logs a message
  indicating the amount of time elapsed."
  []
  (reify PuppetProfiler
    (start [this message metric-id]
      (System/currentTimeMillis))
    (finish [this context message metric-id]
      (log/debugf "[%s] (%d ms) %s"
                  (str/join " " metric-id)
                  (- (System/currentTimeMillis) context)
                  message))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate create-profiler :- Profiler
  "Given profiler configuration information, create and return a new profiler
  (or nil if the configuration indicates that no profiler should be used)."
  [profiler-config :- ProfilerConfig]
  (if (ks/to-bool (get profiler-config :enabled))
    (logging-profiler)))