(ns puppetlabs.services.config.certificate-authority-config-core
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [me.raynes.fs :as fs]
    [puppetlabs.i18n.core :as i18n]
    [puppetlabs.puppetserver.certificate-authority :as ca]
    [puppetlabs.puppetserver.common :as common]
    [puppetlabs.services.config.certificate-authority-schemas :as ca-schemas]
    [schema.core :as schema])
  (:import 
    (java.util.concurrent.locks ReentrantReadWriteLock)))

(def default-allow-subj-alt-names
  false)

(def default-allow-auth-extensions
  false)

(def default-serial-lock-timeout-seconds
  5)

(def default-crl-lock-timeout-seconds
  ;; for large crls, and a slow disk a longer timeout is needed
  60)

(def default-inventory-lock-timeout-seconds
  60)

(schema/defn ^:always-validate initialize-ca-config
  "Adds in default ca config keys/values, which may be overwritten if a value for
  any of those keys already exists in the ca-data"
  [ca-data]
  (let [cadir (:cadir ca-data)
        defaults {:infra-nodes-path (str cadir "/infra_inventory.txt")
                  :infra-node-serials-path (str cadir "/infra_serials")
                  :infra-crl-path (str cadir "/infra_crl.pem")
                  :enable-infra-crl false
                  :allow-subject-alt-names default-allow-subj-alt-names
                  :allow-authorization-extensions default-allow-auth-extensions
                  :serial-lock-timeout-seconds default-serial-lock-timeout-seconds
                  :crl-lock-timeout-seconds default-crl-lock-timeout-seconds
                  :inventory-lock-timeout-seconds default-inventory-lock-timeout-seconds
                  :allow-auto-renewal false
                  :auto-renewal-cert-ttl ca-schemas/default-auto-ttl-renewal
                  :allow-header-cert-info false}]
    (merge defaults ca-data)))

(defn get-ca-ttl
  "Returns ca-ttl value as an integer. If a value is set in certificate-authority that value is returned.
   Otherwise puppet config setting is returned"
  [puppetserver certificate-authority]
  (let [ca-config-value (common/duration-str->sec (:ca-ttl certificate-authority))
        puppet-config-value (:ca-ttl puppetserver)]
    (when (and ca-config-value puppet-config-value)
        (log/warn (i18n/trs "Detected ca-ttl setting in CA config which will take precedence over puppet.conf setting")))
    (or ca-config-value puppet-config-value)))

(schema/defn ^:always-validate
  config->ca-settings :- ca-schemas/CaSettings
  "Given the configuration map from the Puppet Server config
   service return a map with of all the CA settings."
  [{:keys [puppetserver jruby-puppet certificate-authority authorization]}]
  (let [merged (-> (select-keys puppetserver (keys ca-schemas/CaSettings))
                   (merge (select-keys certificate-authority (keys ca-schemas/CaSettings)))
                   (initialize-ca-config))]
    (assoc merged :ruby-load-path (:ruby-load-path jruby-puppet)
           :allow-auto-renewal (:allow-auto-renewal merged)
           :auto-renewal-cert-ttl (common/duration-str->sec (:auto-renewal-cert-ttl merged))
           :ca-ttl (get-ca-ttl puppetserver certificate-authority)
           :allow-header-cert-info (get authorization :allow-header-cert-info false)
           :gem-path (str/join (System/getProperty "path.separator")
                               (:gem-path jruby-puppet))
           :access-control (select-keys certificate-authority
                                        [:certificate-status])
           :serial-lock (new ReentrantReadWriteLock)
           :crl-lock (new ReentrantReadWriteLock)
           :inventory-lock (new ReentrantReadWriteLock))))

(def max-ca-ttl
  "The longest valid duration for CA certs, in seconds. 50 standard years."
  1576800000)

(schema/defn validate-settings!
  "Ensure config values are valid for basic CA behaviors."
  [settings :- ca-schemas/CaSettings]
  (let [ca-ttl (:ca-ttl settings)
        certificate-status-access-control (get-in settings
                                                  [:access-control
                                                   :certificate-status])
        certificate-status-whitelist (:client-whitelist
                                      certificate-status-access-control)]
    (when (> ca-ttl max-ca-ttl)
      (throw (IllegalStateException.
              (i18n/trs "Config setting ca_ttl must have a value below {0}" max-ca-ttl))))
    (cond
      (or (false? (:authorization-required certificate-status-access-control))
          (not-empty certificate-status-whitelist))
      (log/warn
        (format "%s %s"
                (i18n/trs "The ''client-whitelist'' and ''authorization-required'' settings in the ''certificate-authority.certificate-status'' section are deprecated and will be removed in a future release.")
                (i18n/trs "Remove these settings and create an appropriate authorization rule in the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")))
      (not (nil? certificate-status-whitelist))
      (log/warn
        (format "%s %s %s"
                (i18n/trs "The ''client-whitelist'' and ''authorization-required'' settings in the ''certificate-authority.certificate-status'' section are deprecated and will be removed in a future release.")
                (i18n/trs "Because the ''client-whitelist'' is empty and ''authorization-required'' is set to ''false'', the ''certificate-authority.certificate-status'' settings will be ignored and authorization for the ''certificate_status'' endpoints will be done per the authorization rules in the /etc/puppetlabs/puppetserver/conf.d/auth.conf file.")
                (i18n/trs "To suppress this warning, remove the ''certificate-authority'' configuration settings."))))))

(schema/defn ^:always-validate get-custom-oid-mappings :- (schema/maybe ca-schemas/OIDMappings)
  "Given a path to a custom OID mappings file, return a map of all oids to
  shortnames"
  [custom-oid-mapping-file :- schema/Str]
  (if (fs/file? custom-oid-mapping-file)
    (let [oid-mappings (:oid_mapping (common/parse-yaml (slurp custom-oid-mapping-file)))]
      (into {} (for [[oid names] oid-mappings] [(name oid) (keyword (:shortname names))])))
    (log/debug (i18n/trs "No custom OID mapping configuration file found at {0}, custom OID mappings will not be loaded"
                custom-oid-mapping-file))))

(schema/defn ^:always-validate get-oid-mappings :- ca-schemas/OIDMappings
  [custom-oid-mapping-file :- (schema/maybe schema/Str)]
  (merge (get-custom-oid-mappings custom-oid-mapping-file)
         (set/map-invert ca/puppet-short-names)))
