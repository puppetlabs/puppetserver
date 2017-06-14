(ns ^{:doc
       "Core logic for the implementation of the PuppetServerConfigService in
        puppetlabs.services.config.puppet-server-config-service."}
    puppetlabs.services.config.puppet-server-config-core
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :refer [keyset]]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [schema.core :as schema]
            [clojure.string :as str]
            [puppetlabs.i18n.core :as i18n])
  (:import (com.puppetlabs.puppetserver JRubyPuppet)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; config

(def puppet-config-keys
  "The configuration values which, instead of being configured through
  Trapperkeeper's normal configuration service, are read from JRubyPuppet."
  #{:allow-duplicate-certs
    :autosign
    :cacert
    :cacrl
    :cakey
    :ca-name
    :capub
    :ca-ttl
    :certdir
    :certname
    :cert-inventory
    :codedir ; This is not actually needed in Puppet Server, but it's needed in PE (file sync)
    :csrdir
    :csr-attributes
    :dns-alt-names
    :hostcert
    :hostcrl
    :hostprivkey
    :hostpubkey
    :keylength
    :localcacert
    :manage-internal-file-permissions
    :privatekeydir
    :requestdir
    :serial
    :signeddir
    :ssl-client-header
    :ssl-client-verify-header
    :trusted-oid-mapping-file})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; internal helpers

(defn keyword->setting
  [k]
  (-> (name k)
      (.replace "-" "_")))

(defn get-puppet-config-value
  "For a given keyword `k`, returns the configuration value (setting) from
  JRubyPuppet.  Returns `nil` if Puppet does not have a setting for the given
  key.  The keyword will be converted into the appropriate format before it is
  passed to Puppet - for example, if you want the value of Puppet's
  'autoflush' setting, pass in `:autoflush`."
  [jruby-puppet k]
  {:pre [(keyword? k)]}
  (->> (keyword->setting k)
       (.getSetting jruby-puppet)))

(schema/defn get-puppet-config*
  [jruby-puppet :- JRubyPuppet]
  (into {}
        (for [k puppet-config-keys]
          {k (get-puppet-config-value jruby-puppet k)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas

(def Config
  "Represents valid configuration data from Puppet.  Ensures that
    * all config keys are present in the map,
      and there is a non-nil value for each key.
    * :puppet-version is present."
  (assoc
    (zipmap puppet-config-keys (repeat Object))
    :puppet-version String))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public API

(defn validate-tk-config!
  "Ensure that Trapperkeeper's configuration data contains no conflicts with
   the keys we're going to load from JRuby (`puppet-config-keys`)."
  [tk-config]
  (let [key-conflicts (set/intersection (keyset tk-config) puppet-config-keys)]
    (when-not (empty? key-conflicts)
      (throw (Exception.
               (i18n/trs "The following configuration keys conflict with the values to be read from puppet: {0}"
                    (str/join ", " (sort key-conflicts))))))))

(schema/defn ^:always-validate
  get-puppet-config :- Config
  "Returns all of the configuration values for puppetserver from JRubyPuppet."
  [jruby-service]
  {:post [(map? %)]}
  (jruby/with-jruby-puppet
   jruby-puppet jruby-service :get-puppet-config
   (let [config (get-puppet-config* jruby-puppet)]
     (assoc config :puppet-version (.puppetVersion jruby-puppet)))))

(defn init-webserver!
  "Initialize Jetty with paths to the master's SSL certs."
  [override-webserver-settings! webserver-settings puppet-config]
  (let [{:keys [hostcert localcacert cacrl hostprivkey]} puppet-config
        overrides {:ssl-cert     hostcert
                   :ssl-key      hostprivkey
                   :ssl-ca-cert  localcacert
                   :ssl-crl-path cacrl}]
    (if (some #((key %) webserver-settings) overrides)
      (log/info (i18n/trs "Not overriding webserver settings with values from core Puppet"))
      (do
        (log/info (i18n/trs "Initializing webserver settings from core Puppet"))
        (override-webserver-settings! overrides)))))
