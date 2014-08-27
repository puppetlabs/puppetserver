(ns ^{:doc
       "Core logic for the implementation of the PuppetServerConfigService in
        puppetlabs.services.config.puppet-server-config-service."}
    puppetlabs.services.config.puppet-server-config-core
  (:import (com.puppetlabs.puppetserver JRubyPuppet))
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :refer [keyset]]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; config

(def puppet-config-keys-which-require-a-non-nil-value
  "The configuration values which, instead of being configured through
  Trapperkeeper's normal configuration service, are read from JRubyPuppet
  and which are required to have a non-nil value."
  #{:allow-duplicate-certs
    :autosign
    :cacert
    :cacrl
    :cakey
    :ca-name
    :capub
    :ca-ttl
    :certdir
    :cert-inventory
    :certname
    :csrdir
    :hostcert
    :hostprivkey
    :hostpubkey
    :localcacert
    :serial
    :signeddir
    :requestdir
    :dns-alt-names
    :csr-attributes})

(def puppet-config-keys-which-can-have-a-nil-value
  "The configuration values which, instead of being configured through
  Trapperkeeper's normal configuration service, are read from JRubyPuppet
  and which can have a nil value."
  #{:ssl-server-ca-auth})

(def puppet-config-keys
  (set/union puppet-config-keys-which-require-a-non-nil-value
             puppet-config-keys-which-can-have-a-nil-value))

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
  'archive_files' setting, pass in `:archive-files`."
  [jruby-puppet k]
  {:pre [(keyword? k)]}
  (->> (keyword->setting k)
       (.getSetting jruby-puppet)))

(defn get-puppet-config*
  [jruby-puppet]
  {:pre [(instance? JRubyPuppet jruby-puppet)]}
  (into {}
        (for [k puppet-config-keys]
          {k (get-puppet-config-value jruby-puppet k)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas

(def Config
  "Represents valid configuration data from Puppet.  Ensures that
    * all config keys are present in the map
    * a non-nil value is present for any applicable keys
    * :puppet-version is present."
  (-> (zipmap puppet-config-keys-which-require-a-non-nil-value (repeat Object))
      (merge (zipmap puppet-config-keys-which-can-have-a-nil-value
                     (repeat schema/Any)))
      (assoc :puppet-version String)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public API

(defn validate-tk-config!
  "Ensure that Trapperkeeper's configuration data contains no conflicts with
   the keys we're going to load from JRuby (`puppet-config-keys`)."
  [tk-config]
  (let [key-conflicts (set/intersection (keyset tk-config) puppet-config-keys)]
    (when-not (empty? key-conflicts)
      (throw (Exception.
               (str "The following configuration keys "
                    "conflict with the values to be "
                    "read from puppet: " key-conflicts))))))

(schema/defn ^:always-validate
  get-puppet-config :- Config
  "Returns all of the configuration values for puppet-server from JRubyPuppet."
  [jruby-service pool-descriptor]
  {:post [(map? %)]}
  (jruby/with-jruby-puppet
    jruby-puppet jruby-service pool-descriptor
    (let [config (get-puppet-config* jruby-puppet)]
      (assoc config :puppet-version (.puppetVersion jruby-puppet)))))

(defn init-webserver!
  "Initialize Jetty with paths to the master's SSL certs."
  [override-webserver-settings! puppet-config]
  (let [{:keys [hostcert cacert cacrl hostprivkey ssl-server-ca-auth]
         :as   settings}
        puppet-config]
    (log/info "Initializing webserver settings: " settings)
    (override-webserver-settings! {:ssl-cert     hostcert
                                   :ssl-key      hostprivkey
                                   :ssl-ca-cert  (if ssl-server-ca-auth
                                                   ssl-server-ca-auth
                                                   cacert)
                                   :ssl-crl-path cacrl})))
