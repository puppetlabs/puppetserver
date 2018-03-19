(ns puppetlabs.services.jruby.jruby-puppet-core
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.kitchensink.classpath :as ks-classpath]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-puppet-schemas]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.trapperkeeper.services.protocols.metrics :as metrics]
            [puppetlabs.i18n.core :as i18n]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet)
           (clojure.lang IFn)
           (java.util HashMap)
           (com.codahale.metrics MetricRegistry)
           (org.jruby.runtime Constants)))

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

  See also:  http://jruby.org/apidocs/org/jruby/runtime/load/LoadService.html"

  "classpath:/puppetserver-lib")

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

(def default-master-conf-dir
  "/etc/puppetlabs/puppet")

(def default-master-code-dir
  "/etc/puppetlabs/code")

(def default-master-log-dir
  "/var/log/puppetlabs/puppetserver")

(def default-master-run-dir
  "/var/run/puppetlabs/puppetserver")

(def default-master-var-dir
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
    (doseq [[setting dir] [[:master-conf-dir "confdir"]
                           [:master-code-dir "codedir"]
                           [:master-var-dir "vardir"]
                           [:master-run-dir "rundir"]
                           [:master-log-dir "logdir"]]]
      (if-let [value (get config setting)]
        (.put puppet-config dir (ks/absolute-path value))))
    puppet-config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(def facter-jar
  "Well-known name of the facter jar file"
  "facter.jar")

(def MetricsInfo
  {:metric-registry MetricRegistry
   :server-id schema/Str})

(schema/defn ^:always-validate
  add-facter-jar-to-system-classloader!
  "Searches the ruby load path for a file whose name matches that of the
  facter jar file.  The first one found is added to the system classloader's
  classpath.  If no match is found, an info message is written to the log
  but no failure is returned"
  [ruby-load-path :- [schema/Str] ]
  (if-let [facter-jar (first
                       (filter fs/exists?
                               (map #(fs/file % facter-jar) ruby-load-path)))]
    (do
      (log/debug (i18n/trs "Adding facter jar to classpath from: {0}" facter-jar))
      (ks-classpath/add-classpath facter-jar))
    (log/info (i18n/trs "Facter jar not found in ruby load path"))))

(schema/defn get-initialize-pool-instance-fn :- IFn
  [config :- jruby-puppet-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)
   metrics-service]
  (fn [jruby-instance]
    (let [{:keys [http-client-ssl-protocols
                  http-client-cipher-suites
                  http-client-connect-timeout-milliseconds
                  http-client-idle-timeout-milliseconds
                  http-client-metrics-enabled
                  use-legacy-auth-conf]} config
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
          (.put "profiler" profiler)
          (.put "environment_registry" env-registry)
          (.put "http_connect_timeout_milliseconds" http-client-connect-timeout-milliseconds)
          (.put "http_idle_timeout_milliseconds" http-client-idle-timeout-milliseconds)
          (.put "use_legacy_auth_conf" use-legacy-auth-conf))
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

(schema/defn extract-puppet-config
  [config :- {schema/Keyword schema/Any}]
  (select-keys config (keys jruby-puppet-schemas/JRubyPuppetConfig)))

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
   jruby-puppet-config :- {schema/Keyword schema/Any}]
  (-> jruby-puppet-config
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
      (update-in [:master-conf-dir] #(or % default-master-conf-dir))
      (update-in [:master-var-dir] #(or % default-master-var-dir))
      (update-in [:master-code-dir] #(or % default-master-code-dir))
      (update-in [:master-run-dir] #(or % default-master-run-dir))
      (update-in [:master-log-dir] #(or % default-master-log-dir))
      (update-in [:max-requests-per-instance] #(or % 0))
      (update-in [:use-legacy-auth-conf] #(if (some? %) % false))
      (dissoc :environment-class-cache-enabled)))

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
  (let [initialize-pool-instance-fn (get-initialize-pool-instance-fn jruby-puppet-config profiler metrics-service)
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

  The 1-arity function takes only a config and supplies a default of nil for the profiler,
  an empty fn for the agent-shutdown-fn, and suppresses warnings about legacy auth.conf.
  This arity is intended for uses where a jruby-config is required but will not be used
  to create a pool, such as the cli ruby subcommands

  The 5-arity function takes a profiler object and the metrics service. The profiler is placed into
  the puppetserver config through the :initialize-pool-instance lifecycle function. If the
  `http-client -> metrics-enabled` setting is set to true, then the metrics service is used to get a
  metrics registry for the `:puppetserver` domain, and the server id - these are also placed into
  the puppetserver config. The agent-shutdown-fn is run when a jruby-instance is terminated. When
  warn-legacy-auth-conf? is passed in as true, it will log a warning that the use-legacy-auth-conf
  setting is deprecated if the config setting is set to true as well."
  ([raw-config :- {:jruby-puppet {schema/Keyword schema/Any}
                   (schema/optional-key :http-client) {schema/Keyword schema/Any}
                   schema/Keyword schema/Any}]
   (initialize-and-create-jruby-config raw-config nil (fn []) false nil))
  ([raw-config :- {schema/Keyword schema/Any}
    profiler :- (schema/maybe PuppetProfiler)
    agent-shutdown-fn :- IFn
    warn-legacy-auth-conf? :- schema/Bool
    metrics-service]
   (when (get-in raw-config [:jruby-puppet :compat-version])
     (log/errorf "%s"
                 (i18n/trs "The jruby-puppet.compat-version setting is no longer supported."))
     (throw (IllegalArgumentException.
             (i18n/trs "jruby-puppet.compat-version setting no longer supported"))))
   (let [jruby-puppet-config (initialize-puppet-config
                              (extract-http-config (:http-client raw-config))
                              (extract-puppet-config (:jruby-puppet raw-config)))
         uninitialized-jruby-config (-> (extract-jruby-config (:jruby-puppet raw-config))
                                        (assoc :max-borrows-per-instance
                                               (:max-requests-per-instance jruby-puppet-config)))]
     (when (and warn-legacy-auth-conf? (:use-legacy-auth-conf jruby-puppet-config))
       (log/warnf
        "%s %s %s"
        (i18n/trs "The 'jruby-puppet.use-legacy-auth-conf' setting is set to ''true''.")
        (i18n/trs "Support for the legacy Puppet auth.conf file is deprecated and will be removed in a future release.")
        (i18n/trs "Change this setting to 'false' and migrate your authorization rule definitions in the /etc/puppetlabs/puppet/auth.conf file to the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")))
     (create-jruby-config jruby-puppet-config uninitialized-jruby-config agent-shutdown-fn profiler metrics-service))))

(def EnvironmentClassInfoCacheEntry
  "Data structure that holds per-environment cache information for the
  environment_classes info cache"
  {:tag (schema/maybe schema/Str)
   :cache-generation-id schema/Int})

(def EnvironmentClassInfoCache
  "Data structure for the environment_classes info cache"
  {schema/Str EnvironmentClassInfoCacheEntry})

(schema/defn ^:always-validate environment-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Create an environment class info entry.  The return value will have a nil
  tag and cache-generation-id set to 1."
  []
  {:tag nil
   :cache-generation-id 1})

(schema/defn ^:always-validate inc-cache-generation-id-for-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Return the supplied 'original-environment-class-info-entry' class info entry,
  only updated with a cache-generation-id value that has been incremented.
  If the supplied parameter is nil, the return value will have a tag set to
  nil and cache-generation-id set to 1."
  [original-environment-class-info-entry :-
   (schema/maybe EnvironmentClassInfoCacheEntry)]
  (if original-environment-class-info-entry
    (update original-environment-class-info-entry :cache-generation-id inc)
    (environment-class-info-entry)))

(schema/defn ^:always-validate update-environment-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Return the supplied 'original-environment-class-info-entry', only updated
  with the supplied tag and a cache-generation-id value that has been
  incremented."
  [original-environment-class-info-entry :-
   (schema/maybe EnvironmentClassInfoCacheEntry)
   tag :- (schema/maybe schema/Str)]
  (-> original-environment-class-info-entry
      inc-cache-generation-id-for-class-info-entry
      (assoc :tag tag)))

(schema/defn ^:always-valid invalidate-environment-class-info-entry
  :- EnvironmentClassInfoCacheEntry
  "Return the supplied 'original-environment-class-info-entry', only updated
  with a nil tag and a cache-generation-id value that has been incremented."
  [original-environment-class-info-entry :-
   (schema/maybe EnvironmentClassInfoCacheEntry)]
  (update-environment-class-info-entry
   original-environment-class-info-entry
   nil))

(schema/defn ^:always-validate
  environment-class-info-cache-with-invalidated-entry
  :- EnvironmentClassInfoCache
  "Return the supplied 'environment-class-info-cache', only updated with an
  invalidated entry for the supplied 'env-name'.  The invalidated entry will
  have nil tag and a cache-generation-id value that has been incremented."
  [environment-class-info-cache :- EnvironmentClassInfoCache
   env-name :- schema/Str]
  (->> env-name
       (get environment-class-info-cache)
       invalidate-environment-class-info-entry
       (assoc environment-class-info-cache env-name)))

(schema/defn ^:always-validate
  environment-class-info-cache-updated-with-tag :- EnvironmentClassInfoCache
  "Return the supplied environment class info cache argument, updated per
  supplied arguments.  cache-generation-id-before-tag-computed should represent
  what the client received for a
  'get-environment-class-info-cache-generation-id!' call for the environment,
  made before the client started doing the work to parse environment class info
  / compute the new tag.  If cache-generation-id-before-tag-computed equals the
  'cache-generation-id' value stored in the cache for the environment, the new
  'tag' will be stored for the environment and the corresponding
  'cache-generation-id' value will be incremented.  If
  cache-generation-id-before-tag-computed is different than the
  'cache-generation-id' value stored in the cache for the environment, the cache
  will remain unchanged as a result of this call."
  [environment-class-info-cache :- EnvironmentClassInfoCache
   env-name :- schema/Str
   tag :- (schema/maybe schema/Str)
   cache-generation-id-before-tag-computed :- schema/Int]
  (let [cache-entry (get environment-class-info-cache env-name)]
    (if (= (:cache-generation-id cache-entry)
           cache-generation-id-before-tag-computed)
      (assoc environment-class-info-cache
        env-name
        (update-environment-class-info-entry cache-entry tag))
      environment-class-info-cache)))

(schema/defn ^:always-validate
  add-environment-class-info-cache-entry-if-not-present!
  :- EnvironmentClassInfoCache
  "Update the 'environment-class-info-cache' atom with a new cache entry for
  the supplied 'env-name' (if no entry is already present) and return back
  the new value that the atom has been set to."
  [environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)
   env-name :- schema/Str]
  (swap! environment-class-info-cache
         #(if (contains? % env-name)
           %
           (assoc % env-name (environment-class-info-entry)))))

(schema/defn ^:always-validate
  get-environment-class-info-cache-generation-id! :- schema/Int
  "Get the current cache generation id for a specific environment's class info.
  If no entry for the environment had existed at the point this function was
  called this function would, as a side effect, populate a new entry for that
  environment into the cache."
  [environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)
   env-name :- schema/Str]
  (-> (add-environment-class-info-cache-entry-if-not-present!
       environment-class-info-cache
       env-name)
      (get-in [env-name :cache-generation-id])))

(schema/defn ^:always-validate
  mark-environment-expired!
  "Mark the specified environment expired, in all JRuby instances.  Resets
  the cached class info for the environment's 'tag' to nil and increments the
  'cache-generation-id' value."
  [context :- jruby-schemas/PoolContext
   env-name :- schema/Str
   environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)]
  (swap! environment-class-info-cache
         environment-class-info-cache-with-invalidated-entry
         env-name)
  (doseq [jruby-instance (jruby-core/registered-instances context)]
    (-> jruby-instance
        :environment-registry
        (puppet-env/mark-environment-expired! env-name))))

(schema/defn ^:always-validate
  mark-all-environments-expired!
  "Mark all cached environments expired, in all JRuby instances.  Resets the
  cached class info for all previously stored environment 'tags' to nil and
  increments the 'cache-generation-id' value."
  [context :- jruby-schemas/PoolContext
   environment-class-info-cache :- (schema/atom EnvironmentClassInfoCache)]
  (swap! environment-class-info-cache
         (partial ks/mapvals invalidate-environment-class-info-entry))
  (doseq [jruby-instance (jruby-core/registered-instances context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))
