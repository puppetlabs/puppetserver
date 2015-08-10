(ns puppetlabs.puppetserver.environment-cache
  (:require [schema.core :as schema]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def EnvironmentKey
  "Environments are keyed by their names as strings."
  schema/Str)

(def ClassInfo
  "Data describing a single environment. "
  {schema/Any schema/Any})

(def EnvironmentClassInfo
  "A data structure describing one or more envrionments, keyed by
  `EnvironmentKey`."
  {EnvironmentKey ClassInfo})

(def EnvironmentClassInfoCache
  "Schema of an environment info cache."
  (schema/either
    {}
    EnvironmentClassInfo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-cache :- EnvironmentClassInfoCache
  "Create a new cache to store environment class info in."
  []
  {})

(schema/defn ^:always-validate
  reset-cache :- EnvironmentClassInfoCache
  "Resets the given cache to the state provided by `initial-data` and returns
  the new cache. If no initial state is provided then an empty cache is
  returned."
  ([cache :- EnvironmentClassInfoCache
    initial-data :- EnvironmentClassInfo]
    initial-data)
  ([cache :- EnvironmentClassInfoCache]
    (reset-cache cache {})))

(schema/defn ^:always-validate
  store-environment-info :- EnvironmentClassInfo
  "Store one or more environments' class info into the provided cache and
  return the new cache state."
  [cache :- EnvironmentClassInfoCache
   info :- EnvironmentClassInfo]
  (merge cache info))

(schema/defn ^:always-validate
  remove-environment-info :- EnvironmentClassInfoCache
  "Remove an environment from the cache and return the new cache state. If the
  specified environment doesn't exist in the cache, then no operation is
  performed."
  [cache :- EnvironmentClassInfoCache
   env :- EnvironmentKey]
  (dissoc cache env))

(schema/defn ^:always-validate
  get-all-environments-info :- EnvironmentClassInfo
  "Return the info for the environments in the cache. If the cache is empty then
  an empty map is returned. "
  [cache :- EnvironmentClassInfoCache]
  cache)

(schema/defn ^:always-validate
  get-environment-info :- (schema/maybe ClassInfo)
  "Return the info from cache of the provided environment name. If no data is
  found for the provided environment then return nil."
  [cache :- EnvironmentClassInfoCache
   env :- EnvironmentKey]
  (get cache env))

