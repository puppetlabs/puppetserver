(ns puppetlabs.services.protocols.ca)

(defprotocol CaService
  "Describes the functionality of the CA service."
  (initialize-master-ssl! [this master-settings certname]))
