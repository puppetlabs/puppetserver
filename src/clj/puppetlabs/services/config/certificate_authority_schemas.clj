(ns puppetlabs.services.config.certificate-authority-schemas
  (:require
    [puppetlabs.puppetserver.common :as common]
    [puppetlabs.puppetserver.ringutils :as ringutils]
    [schema.core :as schema])
  (:import 
    (java.util.concurrent.locks ReentrantReadWriteLock)))


(def default-auto-ttl-renewal
  "90d") ; 90 days by default

(def default-auto-ttl-renewal-seconds
  (common/duration-str->sec default-auto-ttl-renewal)) ; 90 days by default

(def AccessControl
  "Defines which clients are allowed access to the various CA endpoints.
   Each endpoint has a sub-section containing the client whitelist.
   Currently we only control access to the certificate_status(es) endpoints."
  {(schema/optional-key :certificate-status) ringutils/WhitelistSettings})

(def OIDMappings
  {schema/Str schema/Keyword})

(def TTLDuration
  (schema/cond-pre schema/Int schema/Str))

(def AutoSignInput
  (schema/cond-pre schema/Bool schema/Str))

(def CaSettings
  "Settings from Puppet that are necessary for CA initialization
   and request handling during normal Puppet operation.
   Most of these are Puppet configuration settings."
  {:access-control                   (schema/maybe AccessControl)
   :allow-authorization-extensions   schema/Bool
   :allow-duplicate-certs            schema/Bool
   :allow-subject-alt-names          schema/Bool
   :allow-auto-renewal               schema/Bool
   :auto-renewal-cert-ttl            TTLDuration
   :allow-header-cert-info           schema/Bool
   :autosign                         AutoSignInput
   :cacert                           schema/Str
   :cadir                            schema/Str
   :cacrl                            schema/Str
   :cakey                            schema/Str
   :capub                            schema/Str
   :ca-name                          schema/Str
   :ca-ttl                           schema/Int
   :cert-inventory                   schema/Str
   :csrdir                           schema/Str
   :keylength                        schema/Int
   :manage-internal-file-permissions schema/Bool
   :ruby-load-path                   [schema/Str]
   :gem-path                         schema/Str
   :signeddir                        schema/Str
   :serial                           schema/Str
   ;; Path to file containing list of infra node certificates including MoM
   ;; provisioned by PE or user in case of FOSS
   :infra-nodes-path                 schema/Str
   ;; Path to file containing serial numbers of infra node certificates
   ;; This would be re-generated anytime the infra-nodes list is updated.
   :infra-node-serials-path          schema/Str
   ;; Path to Infrastructure CRL file containing infra certificates
   :infra-crl-path                   schema/Str
   ;; Option to continue using full CRL instead of infra CRL if desired
   ;; Infra CRL would be enabled by default.
   :enable-infra-crl                 schema/Bool
   :serial-lock                      ReentrantReadWriteLock
   :serial-lock-timeout-seconds      common/PosInt
   :crl-lock                         ReentrantReadWriteLock
   :crl-lock-timeout-seconds         common/PosInt
   :inventory-lock                   ReentrantReadWriteLock
   :inventory-lock-timeout-seconds   common/PosInt

   (schema/optional-key :oid-mappings) OIDMappings})
