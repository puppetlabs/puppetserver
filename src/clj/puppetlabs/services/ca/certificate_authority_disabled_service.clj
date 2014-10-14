(ns puppetlabs.services.ca.certificate-authority-disabled-service
  (:require [puppetlabs.services.protocols.ca :refer [CaService]]
            [puppetlabs.trapperkeeper.core :as tk]
            [clojure.tools.logging :as log]))

(tk/defservice certificate-authority-disabled-service
  "Contains a NOOP version of the certificate authority service.
   This is required to maintain service loading order on installations
   which are not running a CA service."
  CaService
  []
  (initialize-master-ssl!
    [this master-settings certname]
    (log/info "CA disabled; ignoring SSL initialization for Master"))

  (retrieve-ca-cert!
    [this localcacert]
    (log/info "CA disabled; ignoring retrieval of CA cert")))
