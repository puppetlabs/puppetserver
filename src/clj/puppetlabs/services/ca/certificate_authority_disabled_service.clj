(ns puppetlabs.services.ca.certificate-authority-disabled-service
  (:require [puppetlabs.services.protocols.ca :refer [CaService]]
            [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

(tk/defservice certificate-authority-disabled-service
  "Contains a NOOP version of the certificate authority service.
   This is required to maintain service loading order on installations
   which are not running a CA service."
  CaService
  [[:AuthorizationService wrap-with-authorization-check]]
  (initialize-master-ssl!
    [this master-settings certname]
    (log/info (i18n/trs "CA disabled; ignoring SSL initialization for Master")))

  (retrieve-ca-cert!
    [this localcacert]
    (log/info (i18n/trs "CA disabled; ignoring retrieval of CA cert")))

  (retrieve-ca-crl!
    [this localcacrl]
    (log/info (i18n/trs "CA disabled; ignoring retrieval of CA CRL")))

  (get-auth-handler
    [this]
    wrap-with-authorization-check))
