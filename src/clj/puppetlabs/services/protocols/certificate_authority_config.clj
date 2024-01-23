(ns puppetlabs.services.protocols.certificate-authority-config)

(defprotocol CertificateAuthorityConfigService
  "The configuration service for the CA  This is built on top of
  Trapperkeeper's normal configuration service and the Puppet Server
  Config Service extensions.  However, this service will handle all CA
  related defaults and appropriately merging relevant TK and Puppet configs."

  (get-config
    [this]
    "Returns a map containing all of the configuration values")

  (get-in-config
    [this ks] [this ks default]
    "Returns the individual configuration value from the nested
    configuration structure, where ks is a sequence of keys.
    Returns nil if the key is not present, or the default value if
    supplied."))
