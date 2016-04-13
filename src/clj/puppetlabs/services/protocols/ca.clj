(ns puppetlabs.services.protocols.ca)

(defprotocol CaService
  "Describes the functionality of the CA service."
  (initialize-master-ssl! [this master-settings certname])
  (retrieve-ca-cert! [this localcacert])
  (retrieve-ca-crl! [this localcacrl])
  (get-auth-handler [this]))
