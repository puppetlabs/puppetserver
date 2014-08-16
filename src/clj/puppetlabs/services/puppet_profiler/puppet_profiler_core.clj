(ns puppetlabs.services.puppet-profiler.puppet-profiler-core
  (:import (com.puppetlabs.puppetserver PuppetProfiler LoggingPuppetProfiler))
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
;;; Public

(schema/defn ^:always-validate create-profiler :- Profiler
  "Given profiler configuration information, create and return a new profiler
  (or nil if the configuration indicates that no profiler should be used)."
  [profiler-config :- ProfilerConfig]
  (if (ks/to-bool (get profiler-config :enabled))
    (LoggingPuppetProfiler.)))