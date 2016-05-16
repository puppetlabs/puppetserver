(ns puppetlabs.services.protocols.puppet-server-config)

(defprotocol PuppetServerConfigService
  "The configuration service for puppetserver.  This is built on top of
  Trapperkeeper's normal configuration service.  It adds a few features -
  most importantly, it merges in settings from the Puppet's 'settings' in ruby.

  It also adds a set of required configuration values and validates them
  during service initialization.

  Note that this is an exact copy of the API from Trapperkeeper's built-in
  configuration service's."

  (get-config [this]
              "Returns a map containing all of the configuration values")

  (get-in-config [this ks] [this ks default]
                 "Returns the individual configuration value from the nested
                 configuration structure, where ks is a sequence of keys.
                 Returns nil if the key is not present, or the default value if
                 supplied."))
