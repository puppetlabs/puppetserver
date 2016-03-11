(ns puppetlabs.puppetserver.common
  (:require [schema.core :as schema]))

(def Environment
  "Schema for environment names. Alphanumeric and _ only."
  (schema/pred (comp not nil? (partial re-matches #"\w+")) "environment"))

(def CodeId
  "Validates that code-id contains only alpha-numerics and
  '-', '_', ';', or ':'."
  (schema/pred (comp not (partial re-find #"[^_\-:;a-zA-Z0-9]")) "code-id"))

(def environment-validation-error-msg
  (partial format "The environment must be purely alphanumeric, not '%s'"))

(def code-id-validation-error-msg
  (partial format "Invalid code-id '%s'. Must contain only alpha-numerics and '-', '_', ';', or ':'"))

