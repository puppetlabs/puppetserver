(ns puppetlabs.puppetserver.common
  (:require [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]))

(def Environment
  "Schema for environment names. Alphanumeric and _ only."
  (schema/pred (comp not nil? (partial re-matches #"\w+")) "environment"))

(def CodeId
  "Validates that code-id contains only alpha-numerics and
  '-', '_', ';', or ':'."
  (schema/pred (comp not (partial re-find #"[^_\-:;a-zA-Z0-9]")) "code-id"))

(schema/defn environment-validation-error-msg
  [environment :- schema/Str]
  (i18n/tru "The environment must be purely alphanumeric, not ''{0}''"
            environment))

(schema/defn code-id-validation-error-msg
  [code-id :- schema/Str]
  (i18n/tru "Invalid code-id ''{0}''. Must contain only alpha-numerics and ''-'', ''_'', '';'', or '':''"
            code-id))

