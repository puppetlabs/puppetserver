(ns puppetlabs.services.jruby.jruby-puppet-testutils
  (:require [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-puppet-schemas]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-service]
            [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.trapperkeeper.services.metrics.metrics-service :as metrics]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-puppet]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as jruby-pool-manager]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as scheduler-service]
            [puppetlabs.trapperkeeper.services.status.status-service :as status-service]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services :as tk-services])
  (:import (clojure.lang IFn)
           (com.puppetlabs.jruby_utils.jruby ScriptingContainer)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)
           (com.puppetlabs.puppetserver JRubyPuppet JRubyPuppetResponse)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib" "./ruby/hiera/lib"])
(def gem-home "./target/jruby-gem-home")
(def gem-path "./target/jruby-gem-home:./target/vendored-jruby-gems")

(def conf-dir "./target/master-conf")
(def code-dir "./target/master-code")
(def var-dir "./target/master-var")
(def run-dir "./target/master-var/run")
(def log-dir "./target/master-var/log")

(def jruby-service-and-dependencies
  [jruby-puppet/jruby-puppet-pooled-service
   profiler/puppet-profiler-service
   jruby-pool-manager/jruby-pool-manager-service
   metrics/metrics-service
   scheduler-service/scheduler-service
   status-service/status-service
   jetty9-service/jetty9-service
   webrouting-service/webrouting-service])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas

(def JRubyPuppetTKConfig
  "Schema combining JRubyPuppetConfig and JRubyConfig.
  This represents what would be in a real TK configuration's jruby-puppet section,
  so we remove some things from the JRubyConfig:
  - remove :max-borrows-per-instance (keep :max-requests-per-instance)
  - remove :lifecycle"
  (-> jruby-puppet-schemas/JRubyPuppetConfig
      (merge jruby-schemas/JRubyConfig)
      (dissoc :max-borrows-per-instance
              :lifecycle)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test util functions

(defn borrow-instance
  "Borrows an instance from the JRubyPuppet interpreter pool. If there are no
  interpreters left in the pool then the operation blocks until there is one
  available. A timeout (integer measured in milliseconds) can be configured
  which will either return an interpreter if one is available within the
  timeout length, or will return nil after the timeout expires if no
  interpreters are available. This timeout defaults to 1200000 milliseconds.

  `reason` is an identifier (usually a map) describing the reason for borrowing the
  JRuby instance.  It may be used for metrics and logging purposes."
 [jruby-puppet-service reason]
 (let [{:keys [pool-context]} (tk-service/service-context jruby-puppet-service)
       event-callbacks (jruby-core/get-event-callbacks pool-context)]
   (jruby-core/borrow-from-pool-with-timeout pool-context reason event-callbacks)))

(defn return-instance
  "Returns the JRubyPuppet interpreter back to the pool.

  `reason` is an identifier (usually a map) describing the reason for borrowing the
  JRuby instance.  It may be used for metrics and logging purposes, so for
  best results it should be set to the same value as it was set during the
  `borrow-instance` call."
 [jruby-puppet-service jruby-instance reason]
 (let [pool-context (:pool-context (tk-service/service-context jruby-puppet-service))
       event-callbacks (jruby-core/get-event-callbacks pool-context)]
   (jruby-core/return-to-pool jruby-instance reason event-callbacks)))

(schema/defn create-mock-scripting-container :- ScriptingContainer
  []
  (reify ScriptingContainer
    (terminate [_])))

(schema/defn ^:always-validate
create-mock-pool-instance :- JRubyInstance
  [mock-jruby-instance-creator-fn :- IFn
   pool :- jruby-schemas/pool-queue-type
   id :- schema/Int
   config :- jruby-schemas/JRubyConfig
   flush-instance-fn :- IFn]
  (let [instance (jruby-schemas/map->JRubyInstance
                  {:id id
                   :internal {:pool pool
                              :max-borrows (:max-borrows-per-instance config)
                              :initial-borrows nil
                              :flush-instance-fn flush-instance-fn
                              :state (atom {:borrow-count 0})}
                   :scripting-container (create-mock-scripting-container)})
        modified-instance (merge instance {:jruby-puppet (mock-jruby-instance-creator-fn)
                                           :environment-registry (puppet-env/environment-registry)})]
    (.register pool modified-instance)
    modified-instance))

(defn jruby-puppet-tk-config
  "Create a JRubyPuppet pool config with the given pool config.  Suitable for use
  in bootstrapping trapperkeeper (in other words, returns a representation of the
  config that matches what would be read directly from the config files on disk,
  as opposed to a version that has been processed and transformed to comply
  with the JRubyPuppetConfig schema)."
  [pool-config]
  {:product       {:name              "puppetserver"
                   :update-server-url "http://localhost:11111"}
   :jruby-puppet  pool-config
   :http-client {}
   :metrics {:server-id "localhost"}
   :authorization {:version 1
                   :rules [{:match-request {:path "/" :type "path"}
                            :allow "*"
                            :sort-order 1
                            :name "allow all"}]}
   :webserver {:host "localhost"}
   :web-router-service {:puppetlabs.trapperkeeper.services.status.status-service/status-service
                        "/status"}})

(schema/defn ^:always-validate
  jruby-puppet-config :- JRubyPuppetTKConfig
  "Create a JRubyPuppetConfig for testing. The optional map argument `options` may
  contain a map, which, if present, will be merged into the final JRubyPuppetConfig
  map.  (This function differs from `jruby-puppet-tk-config` in
  that it returns a map that complies with the JRubyPuppetConfig schema, which
  differs slightly from the raw format that would be read from config files
  on disk.)"
  ([]
   (let [combined-configs
         (merge (jruby-puppet-core/initialize-puppet-config
                 {}
                 {:master-conf-dir conf-dir
                  :master-code-dir code-dir
                  :master-var-dir var-dir
                  :master-run-dir run-dir
                  :master-log-dir log-dir
                  :use-legacy-auth-conf false})
                (jruby-core/initialize-config {:ruby-load-path ruby-load-path
                                               :gem-home gem-home
                                               :gem-path gem-path}))
         max-requests-per-instance (:max-borrows-per-instance combined-configs)
         updated-config (-> combined-configs
                            (assoc :max-requests-per-instance max-requests-per-instance)
                            (dissoc :max-borrows-per-instance))]
     (dissoc updated-config :lifecycle)))
  ([options]
   (merge (jruby-puppet-config) options)))

(defn drain-pool
  "Drains the JRubyPuppet pool and returns each instance in a vector."
  [pool-context size]
  (mapv (fn [_] (jruby-core/borrow-from-pool pool-context :test [])) (range size)))

(defn fill-drained-pool
  "Returns a list of JRubyPuppet instances back to their pool."
  [instance-list]
  (doseq [instance instance-list]
    (jruby-core/return-to-pool instance :test [])))

(defn reduce-over-jrubies!
  "Utility function; takes a JRuby pool and size, and a function f from integer
  to string.  For each JRuby instance in the pool, f will be called, passing in
  an integer offset into the jruby array (0..size), and f is expected to return
  a string containing a script to run against the jruby instance.

  Returns a vector containing the results of executing the scripts against the
  JRuby instances."
  [pool-context size f]
  (let [jrubies (drain-pool pool-context size)
        result  (reduce
                  (fn [acc jruby-offset]
                    (let [sc (:scripting-container (nth jrubies jruby-offset))
                          script (f jruby-offset)
                          result (.runScriptlet sc script)]
                      (conj acc result)))
                  []
                  (range size))]
    (fill-drained-pool jrubies)
    result))

(defn wait-for-jrubies
  "Wait for all jrubies to land in the JRubyPuppetService's pool"
  [app]
  (let [pool-context (-> app
                         (tk-app/get-service :JRubyPuppetService)
                         tk-service/service-context
                         :pool-context)
        num-jrubies (-> pool-context
                        jruby-core/get-pool-state
                        :size)]
    (while (< (count (jruby-core/registered-instances pool-context))
              num-jrubies)
      (Thread/sleep 100))))

(schema/defn ^:always-validate
  mock-puppet-config-settings :- {schema/Str schema/Any}
  "Return a map of settings that mock the settings that core Ruby Puppet
  would return via a call to JRubyPuppet.getSetting()."
  [jruby-puppet-config :- {:master-conf-dir schema/Str
                           :master-code-dir schema/Str
                           schema/Keyword schema/Any}]
  (let [certname "localhost"
        confdir (:master-conf-dir jruby-puppet-config)
        ssldir (str confdir "/ssl")
        certdir (str ssldir "/certs")
        cadir (str ssldir "/ca")
        private-key-dir (str ssldir "/private_keys")]
    {"allow_duplicate_certs" false
     "autosign" true
     "keylength" 2048
     "cacert" (str cadir "/ca_crt.pem")
     "ca_name" (str "Puppet CA: " certname)
     "cacrl" (str cadir "/ca_crl.pem")
     "cakey" (str cadir "/ca_key.pem")
     "capub" (str cadir "/ca_pub.pem")
     "ca_ttl" 157680000
     "certdir" certdir
     "certname" certname
     "cert_inventory" (str cadir "/inventory.txt")
     "codedir" (:master-code-dir jruby-puppet-config)
     "csr_attributes" (str confdir "/csr_attributes.yaml")
     "csrdir" (str cadir "/requests")
     "dns_alt_names" ""
     "hostcert" (str certdir "/" certname ".pem")
     "hostcrl" (str ssldir "/crl.pem")
     "hostprivkey" (str private-key-dir "/" certname ".pem")
     "hostpubkey" (str ssldir "/public_keys/" certname ".pem")
     "localcacert" (str certdir "/ca.pem")
     "manage_internal_file_permissions" true
     "privatekeydir" private-key-dir
     "requestdir" (str ssldir "/certificate_requests")
     "serial" (str cadir "/serial")
     "signeddir" (str cadir "/signed")
     "ssl_client_header" "HTTP_X_CLIENT_DN"
     "ssl_client_verify_header" "HTTP_X_CLIENT_VERIFY"
     "trusted_oid_mapping_file" (str confdir
                                     "/custom_trusted_oid_mapping.yaml")}))

(schema/defn ^:always-validate
  create-mock-jruby-puppet :- JRubyPuppet
  "Create a 'mock' JRubyPuppet instance which returns fixed values for settings
  and puppet version and a hard-coded HTTP 200 response for any requests it
  handles."
  ([config :- {schema/Keyword schema/Any}]
   (create-mock-jruby-puppet
    (fn [_]
      (throw (UnsupportedOperationException. "Mock handleRequest not defined")))
    config))
  ([handle-request-fn :- IFn
    config :- {schema/Keyword schema/Any}]
   (let [puppet-config (merge
                        (mock-puppet-config-settings (:jruby-puppet config))
                        (:puppet config))]
     (reify JRubyPuppet
       (getSetting [_ setting]
         (let [value (get puppet-config setting :not-found)]
           (if (= value :not-found)
             (throw (IllegalArgumentException.
                     (str "Setting not in mock-puppet-config-settings "
                          "requested: " setting ". Add an appropriate value "
                          "to the map to correct this problem.")))
             value)))
       (puppetVersion [_]
         "1.2.3")
       (handleRequest [_ request]
         (handle-request-fn request))
       (terminate [_])))))

(schema/defn ^:always-validate
  create-mock-jruby-puppet-fn-with-handle-response-params :- IFn
  "Return a function which, when invoked, will create a mock JRubyPuppet
  instance.  Supplied arguments - 'status-code', 'response-body',
  'response-content-type', and 'puppet-version' are returned in the
  'JRubyPuppetResponse' that the JRubyPuppet instance's .handleRequest
  method returns when invoked.  The default value for 'response-content-type',
  if not supplied, is 'text/plain'.  The default value for 'puppet-version',
  if not supplied, is '1.2.3'."
  ([status-code :- schema/Int
    response-body :- schema/Str]
   (create-mock-jruby-puppet-fn-with-handle-response-params status-code
                                                            response-body
                                                            "text/plain"))
  ([status-code :- schema/Int
    response-body :- schema/Str
    response-content-type :- schema/Str]
   (create-mock-jruby-puppet-fn-with-handle-response-params status-code
                                                            response-body
                                                            response-content-type
                                                            "1.2.3"))
  ([status-code :- schema/Int
    response-body :- schema/Str
    response-content-type :- schema/Str
    puppet-version :- schema/Str]
   (partial create-mock-jruby-puppet
            (fn [_]
              (JRubyPuppetResponse.
               (Integer. status-code)
               response-body
               response-content-type
               puppet-version)))))

(schema/defn ^:always-validate
  create-mock-pool :- jruby-schemas/PoolContext
  "Create a 'mock' JRuby pool.  The pool is filled with the number 'mock'
   JRubyPuppet instances specified in the jruby-config.  The supplied
   'mock-jruby-puppet-fn' is invoked for each instance to be created for the
   pool and is expected to return an object of type 'JRubyPuppet'."
  [jruby-config :- jruby-schemas/JRubyConfig
   mock-jruby-puppet-fn :- IFn]
  ;; The implementation of this function is based on and very similar to
  ;; `prime-pool!` in `puppetlabs.services.jruby-pool-manager.impl.jruby-agents`
  ;; from the jruby-utils library.
  (let [pool-context (jruby-pool-manager-core/create-pool-context jruby-config)
        pool (jruby-internal/get-pool pool-context)
        count (.remainingCapacity pool)]
    (dotimes [i count]
      (let [id (inc i)]
        (create-mock-pool-instance
         mock-jruby-puppet-fn
         pool
         id
         jruby-config
         (constantly nil))))
    pool-context))

(schema/defn ^:always-validate
  mock-jruby-pool-manager-service
  :- (schema/protocol tk-service/ServiceDefinition)
  "Create a 'mock' JRubyPoolManagerService, with a create-pool function
  which returns a 'mock' JRuby pool when called.  The supplied
  'mock-jruby-puppet-fn' is invoked for each instance to be created for the pool
  and is expected to return an object of type 'JRubyPuppet'.  The 'config'
  option is passed back as an argument to 'mock-jruby-puppet-fn', if supplied."
  ([config :- {schema/Keyword schema/Any}]
   (mock-jruby-pool-manager-service config
                                    create-mock-jruby-puppet))
  ([config :- {schema/Keyword schema/Any}
    mock-jruby-puppet-fn :- IFn]
   (tk-service/service
    pool-manager-protocol/PoolManagerService
    []
    (create-pool
     [this jruby-config]
     (create-mock-pool jruby-config (partial mock-jruby-puppet-fn config))))))

(defn add-mock-jruby-pool-manager-service
  ([services config]
   (add-mock-jruby-pool-manager-service services config create-mock-jruby-puppet))
  ([services config mock-jruby-puppet-fn]
   (->> services
        (remove #(= :PoolManagerService (tk-services/service-def-id %)))
        vec
        (cons (mock-jruby-pool-manager-service
               config
               mock-jruby-puppet-fn)))))

(defn jruby-service-and-dependencies-with-mocking
  [config]
  (add-mock-jruby-pool-manager-service
   jruby-service-and-dependencies
   config))
