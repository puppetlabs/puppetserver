(ns puppetlabs.services.jruby.jruby-puppet-core
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-puppet-schemas]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.trapperkeeper.services.protocols.metrics :as metrics]
            [puppetlabs.i18n.core :as i18n]
            [clojure.string :as str])
  (:import (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet)
           (clojure.lang IFn)
           (java.util HashMap)
           (com.codahale.metrics MetricRegistry)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def ruby-code-dir
  "The name of the directory containing the ruby code in this project.

  This directory is relative to `src/ruby` and works from source because the
  `src/ruby` directory is defined as a resource in `project.clj` which places
  the directory on the classpath which in turn makes the directory available on
  the JRuby load path.  Similarly, this works from the uberjar because this
  directory is placed into the root of the jar structure which is on the
  classpath.

  See also:  https://www.javadoc.io/doc/org.jruby/jruby-core/latest/org/jruby/runtime/load/LoadService.html"

  "uri:classloader:/puppetserver-lib")

(def default-http-connect-timeout
  "The default number of milliseconds that the client will wait for a connection
  to be established. Currently set to 2 minutes."
  (* 2 60 1000))

(def default-http-socket-timeout
  "The default number of milliseconds that the client will allow for no data to
  be available on the socket. Currently set to 20 minutes."
  (* 20 60 1000))

(def default-http-metrics-enabled
  "The default for whether or not to enable http client metrics. Currently set to true."
  true)

(def default-server-conf-dir
  "/etc/puppetlabs/puppet")

(def default-server-code-dir
  "/etc/puppetlabs/code")

(def default-server-log-dir
  "/var/log/puppetlabs/puppetserver")

(def default-server-run-dir
  "/var/run/puppetlabs/puppetserver")

(def default-server-var-dir
  "/opt/puppetlabs/server/data/puppetserver")

(def default-vendored-gems-dir
  "/opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn ^:always-validate managed-load-path :- [schema/Str]
  "Return a list of ruby LOAD_PATH directories built from the
  user-configurable ruby-load-path setting of the jruby-puppet configuration."
  [ruby-load-path :- [schema/Str]]
  (cons ruby-code-dir ruby-load-path))

(schema/defn ^:always-validate config->puppet-config :- HashMap
  "Given the raw jruby-puppet configuration section, return a
  HashMap with the configuration necessary for ruby Puppet."
  [config :- jruby-puppet-schemas/JRubyPuppetConfig]
  (let [puppet-config (new HashMap)]
    (doseq [[setting-name dir] [[:server-conf-dir "confdir"]
                                [:server-code-dir "codedir"]
                                [:server-var-dir "vardir"]
                                [:server-run-dir "rundir"]
                                [:server-log-dir "logdir"]]]
      (when-let [value (get config setting-name)]
        (.put puppet-config dir (ks/absolute-path value))))
    (when (:disable-i18n config)
      ; The value for disable-i18n is stripped in Puppet::Server::PuppetConfig
      ; so that only the key is outputted, so that '--disable_18n' is used, not
      ; '--disable_i18n true'
      (.put puppet-config "disable_i18n" true))
    puppet-config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(def MetricsInfo
  {:metric-registry MetricRegistry
   :server-id schema/Str})

(schema/defn get-initialize-pool-instance-fn :- IFn
  [config :- jruby-puppet-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)
   metrics-service
   multithreaded :- schema/Bool]
  (fn [jruby-instance]
    (let [{:keys [http-client-ssl-protocols
                  http-client-cipher-suites
                  http-client-connect-timeout-milliseconds
                  http-client-idle-timeout-milliseconds
                  http-client-metrics-enabled
                  track-lookups]} config
          scripting-container (:scripting-container jruby-instance)]

      (.runScriptlet scripting-container "require 'puppet/server/master'")
      (let [ruby-puppet-class (.runScriptlet scripting-container "Puppet::Server::Master")
            puppet-config (config->puppet-config config)
            puppetserver-config (HashMap.)
            env-registry (puppet-env/environment-registry)]
        (when http-client-ssl-protocols
          (.put puppetserver-config "ssl_protocols" (into-array String http-client-ssl-protocols)))
        (when http-client-cipher-suites
          (.put puppetserver-config "cipher_suites" (into-array String http-client-cipher-suites)))
        (when http-client-metrics-enabled
          (doto puppetserver-config
            (.put "metric_registry" (metrics/get-metrics-registry metrics-service :puppetserver))
            (.put "server_id" (metrics/get-server-id metrics-service))))
        (doto puppetserver-config
          (.put "multithreaded" multithreaded)
          (.put "track_lookups" track-lookups)
          (.put "profiler" profiler)
          (.put "environment_registry" env-registry)
          (.put "http_connect_timeout_milliseconds" http-client-connect-timeout-milliseconds)
          (.put "http_idle_timeout_milliseconds" http-client-idle-timeout-milliseconds))
        (let [jruby-puppet (.callMethodWithArgArray
                            scripting-container
                            ruby-puppet-class
                            "new"
                            (into-array Object
                                        [puppet-config puppetserver-config])
                            JRubyPuppet)]
          (-> jruby-instance
              (assoc :jruby-puppet jruby-puppet)
              (assoc :environment-registry env-registry)))))))

(schema/defn cleanup-fn
  [instance]
  (.terminate (:jruby-puppet instance)))

(schema/defn extract-jruby-config
  [config :- {schema/Keyword schema/Any}]
  (select-keys config (keys jruby-schemas/JRubyConfig)))

(schema/defn multithreaded?
  [config :- {schema/Keyword schema/Any}]
  (get config :multithreaded false))

(schema/defn extract-puppet-config
  [config :- {schema/Keyword schema/Any}]
  (select-keys config (map schema/explicit-schema-key (keys jruby-puppet-schemas/JRubyPuppetConfig))))

(schema/defn extract-http-config
  "The config is allowed to be nil because the http-client section isn't
  required in puppetserver's tk config"
  [config :- (schema/maybe {schema/Keyword schema/Any})]
  (select-keys config [:ssl-protocols
                       :cipher-suites
                       :connect-timeout-milliseconds
                       :idle-timeout-milliseconds
                       :metrics-enabled]))

(schema/defn ^:always-validate initialize-gem-path
  [{:keys [gem-home gem-path] :as jruby-config} :- {schema/Keyword schema/Any}]
  (if gem-path
    (assoc jruby-config :gem-path (str/join ":" gem-path))
    (assoc jruby-config :gem-path (str gem-home ":" default-vendored-gems-dir))))

(schema/defn ^:always-validate
  initialize-puppet-config :- jruby-puppet-schemas/JRubyPuppetConfig
  [http-config :- {schema/Keyword schema/Any}
   jruby-puppet-config :- {schema/Keyword schema/Any}
   multithreaded :- schema/Bool]
  (when multithreaded
    (log/info (i18n/trs "Disabling i18n for puppet because using multithreaded jruby")))
  (let [config (-> jruby-puppet-config
                 (assoc :http-client-ssl-protocols (:ssl-protocols http-config))
                 (assoc :http-client-cipher-suites (:cipher-suites http-config))
                 (assoc :http-client-connect-timeout-milliseconds
                        (get http-config :connect-timeout-milliseconds
                             default-http-connect-timeout))
                 (assoc :http-client-idle-timeout-milliseconds
                        (get http-config :idle-timeout-milliseconds
                             default-http-socket-timeout))
                 (assoc :http-client-metrics-enabled
                        (get http-config :metrics-enabled default-http-metrics-enabled))
                 (update-in [:server-conf-dir] #(or % (:master-conf-dir jruby-puppet-config) default-server-conf-dir))
                 (update-in [:server-var-dir] #(or % (:master-var-dir jruby-puppet-config) default-server-var-dir))
                 (update-in [:server-code-dir] #(or % (:master-code-dir jruby-puppet-config) default-server-code-dir))
                 (update-in [:server-run-dir] #(or % (:master-run-dir jruby-puppet-config) default-server-run-dir))
                 (update-in [:server-log-dir] #(or % (:master-log-dir jruby-puppet-config) default-server-log-dir))
                 (update-in [:max-requests-per-instance] #(or % 0))
                 (assoc :disable-i18n multithreaded)
                 (dissoc :environment-class-cache-enabled))]
    (assoc config
           :master-conf-dir (:server-conf-dir config)
           :master-var-dir (:server-var-dir config)
           :master-code-dir (:server-code-dir config)
           :master-run-dir (:server-run-dir config)
           :master-log-dir (:server-log-dir config))))

(schema/defn create-jruby-config :- jruby-schemas/JRubyConfig
  "Handles creating a valid JRubyConfig map for use in the jruby-puppet-service.
  This method:
  * Creates the appropriate lifecycle functions
  * overrides the default ruby-load-path to include the ruby code directory from
    this project"
  [jruby-puppet-config :- jruby-puppet-schemas/JRubyPuppetConfig
   jruby-config :- {schema/Keyword schema/Any}
   agent-shutdown-fn :- IFn
   profiler :- (schema/maybe PuppetProfiler)
   metrics-service]
  (let [initialize-pool-instance-fn (get-initialize-pool-instance-fn
                                     jruby-puppet-config profiler
                                     metrics-service
                                     (multithreaded? jruby-config))
        lifecycle-fns {:shutdown-on-error agent-shutdown-fn
                       :initialize-pool-instance initialize-pool-instance-fn
                       :cleanup cleanup-fn}
        modified-jruby-config (-> jruby-config
                                  (assoc :ruby-load-path (managed-load-path
                                                          (:ruby-load-path jruby-config)))
                                  initialize-gem-path)]
    (jruby-core/initialize-config (assoc modified-jruby-config :lifecycle lifecycle-fns))))

(schema/defn initialize-and-create-jruby-config :- jruby-schemas/JRubyConfig
  "Handles the initialization of the jruby-puppet config (from the puppetserver.conf file),
  for the purpose of converting it to the structure required by the jruby-utils
  library (puppetlabs.services.jruby-pool-manager.jruby-schemas/JRubyConfig).
  This function will use data from the :jruby-puppet and :http-client sections
  of puppetserver.conf, from raw-config. If values are not provided, everything in
  :http-client :jruby-puppet will be given default values, except for
  :ruby-load-path and :gem-home, which are required.

  The 1-arity function takes only a config and supplies a default of nil for the profiler
  and an empty fn for the agent-shutdown-fn. This arity is intended for uses where a
  jruby-config is required but will not be used to create a pool, such as the cli ruby
  subcommands.

  The 5-arity function takes a profiler object and the metrics service. The profiler is placed into
  the puppetserver config through the :initialize-pool-instance lifecycle function. If the
  `http-client -> metrics-enabled` setting is set to true, then the metrics service is used to get a
  metrics registry for the `:puppetserver` domain, and the server id - these are also placed into
  the puppetserver config. The agent-shutdown-fn is run when a jruby-instance is terminated."
  ([raw-config :- {:jruby-puppet {schema/Keyword schema/Any}
                   (schema/optional-key :http-client) {schema/Keyword schema/Any}
                   schema/Keyword schema/Any}]
   (initialize-and-create-jruby-config raw-config nil (fn []) nil))
  ([raw-config :- {schema/Keyword schema/Any}
    profiler :- (schema/maybe PuppetProfiler)
    agent-shutdown-fn :- IFn
    metrics-service]
   (when (get-in raw-config [:jruby-puppet :compat-version])
     (log/errorf "%s"
                 (i18n/trs "The jruby-puppet.compat-version setting is no longer supported."))
     (throw (IllegalArgumentException.
             (i18n/trs "jruby-puppet.compat-version setting no longer supported"))))
   (let [jruby-puppet (:jruby-puppet raw-config)
         jruby-puppet-config (initialize-puppet-config
                              (extract-http-config (:http-client raw-config))
                              (extract-puppet-config jruby-puppet)
                              (multithreaded? jruby-puppet))
         uninitialized-jruby-config (-> (extract-jruby-config (:jruby-puppet raw-config))
                                        (assoc :max-borrows-per-instance
                                               (:max-requests-per-instance jruby-puppet-config)))]
     (create-jruby-config jruby-puppet-config uninitialized-jruby-config agent-shutdown-fn profiler metrics-service))))

(def EnvironmentCacheEntry
  "Represents an environment with each cacheable info service as a key.
  The value for each info service is a map that contains a:

    `:tag` Representing the latest value computed for that info service
           (this is the sanitized Etag used in subsequent HTTP cache checks).

    `:version` Representing the version of the environment's content at a
               given tag. This is incremented whenever the environment cache
               is invalidated or new information is retrieved from the
               environment. New tag values are only accepted if they have a
               content-version parameter that matches the **currently** stored
               version, signifying that the cache was not invalidated while
               the tag was being computed."
  {:classes {:tag (schema/maybe schema/Str) :version schema/Int}
   :transports {:tag (schema/maybe schema/Str) :version schema/Int}})

(def EnvironmentCache
  "Maps each environment to its cache entry"
  {schema/Str EnvironmentCacheEntry})

(schema/defn ^:always-validate environment-cache-entry :- EnvironmentCacheEntry
  "Creates an initial EnvironmentCacheEntry"
  []
  {:classes {:tag nil :version 1}
   :transports {:tag nil :version 1}})

(schema/defn ^:always-validate invalidate-environment-cache-entry
  :- EnvironmentCacheEntry
  "Sets all info service tags to nil and increments their content versions"
  [maybe-environment-cache-entry :- (schema/maybe EnvironmentCacheEntry)]
  (-> (or maybe-environment-cache-entry (environment-cache-entry))
    (update-in [:classes :version] inc)
    (update-in [:transports :version] inc)
    (assoc-in [:classes :tag] nil)
    (assoc-in [:transports :tag] nil)))

(schema/defn ^:always-validate invalidate-environment :- EnvironmentCache
  "Return the EnvironmentCache with named environment invalidated."
  [environment-cache :- EnvironmentCache
   env-name :- schema/Str]
  (->> env-name
       (get environment-cache)
       invalidate-environment-cache-entry
       (assoc environment-cache env-name)))

(schema/defn ^:always-validate
  environment-class-info-cache-updated-with-tag :- EnvironmentCache
  "DEPRECATED: see `maybe-update-environment-info-service-cache`

  Updates the class info service tag in the given EnvironmentCache for the
  named environment, **if** the prior-content-version and the version currently
  in the cache are the same. If the two content versions are not the same, eg
  the cache has been invalidated, or otherwise moved since the action that
  computed the tag started, then the update will silently fail. Returns the
  given EnvironmentCache regardless of update status."
  [environment-cache :- EnvironmentCache
   env-name :- schema/Str
   tag :- (schema/maybe schema/Str)
   prior-content-version :- schema/Int]
  (if (= (get-in environment-cache [env-name :classes :version])
         prior-content-version)
    (-> environment-cache
      (assoc-in [env-name :classes :tag] tag)
      (update-in [env-name :classes :version] inc))
    environment-cache))

(schema/defn ^:always-validate
  maybe-update-environment-info-service-cache :- EnvironmentCache
  "Updates the requested info service tag in the given EnvironmentCache for
  the named environment, **if** the prior-content-version and the version
  currently in the cache are the same. If the two content versions are not the
  same, eg the cache has been invalidated, or otherwise moved since the action
  that computed the tag started, then the update will silently fail. Returns
  the given EnvironmentCache regardless of update status."
  [environment-cache :- EnvironmentCache
   env-name :- schema/Str
   svc-id :- schema/Keyword
   tag :- (schema/maybe schema/Str)
   prior-content-version :- schema/Int]
  (if (= (get-in environment-cache [env-name svc-id :version])
         prior-content-version)
    (-> environment-cache
        (assoc-in [env-name svc-id :tag] tag)
        (update-in [env-name svc-id :version] inc))
    environment-cache))

(schema/defn ^:always-validate ensure-environment :- EnvironmentCache
  "Ensure the given environment exists within the EnvironmentCache."
  [environment-cache :- (schema/atom EnvironmentCache)
   env-name :- schema/Str]
  (swap! environment-cache
         #(if (contains? % env-name)
           %
           (assoc % env-name (environment-cache-entry)))))

(schema/defn ^:always-validate
  get-environment-class-info-cache-generation-id! :- schema/Int
  "DEPRECATED: see `get-info-service-version` for replacement.

  Get the current cache generation id for a specific environment's info cache.
  If no entry for the environment had existed at the point this function was
  called this function would, as a side effect, populate a new entry for that
  environment into the cache."
  [environment-cache :- (schema/atom EnvironmentCache)
   env-name :- schema/Str]
  (-> (ensure-environment environment-cache env-name)
      (get-in [env-name :classes :version])))

(schema/defn ^:always-validate get-cached-content-version :- schema/Int
  "Get the current version for a specific service's content within an
  environment, initializes environment if it did not already exist."
  [environment-cache :- (schema/atom EnvironmentCache)
   env-name :- schema/Str
   info-service :- schema/Keyword]
  (-> (ensure-environment environment-cache env-name)
      (get-in [env-name info-service :version])))

(schema/defn ^:always-validate mark-environment-expired!
  "Mark the specified environment expired in the clojure managed cache and,
  using the Environment Expiration Service, in each JRuby instance."
  [context :- jruby-schemas/PoolContext
   env-name :- schema/Str
   environment-cache :- (schema/atom EnvironmentCache)]
  (swap! environment-cache
         invalidate-environment
         env-name)
  (doseq [jruby-instance (jruby-core/registered-instances context)]
    (-> jruby-instance
        :environment-registry
        (puppet-env/mark-environment-expired! env-name))))

(schema/defn ^:always-validate
  mark-all-environments-expired!
  "Mark all cached environments expired, in all JRuby instances."
  [context :- jruby-schemas/PoolContext
   environment-cache :- (schema/atom EnvironmentCache)]
  (swap! environment-cache
         (partial ks/mapvals invalidate-environment-cache-entry))
  (doseq [jruby-instance (jruby-core/registered-instances context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))
