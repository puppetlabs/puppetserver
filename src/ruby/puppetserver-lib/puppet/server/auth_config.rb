## This is a "dummy" AuthConfig that Puppet Server registers with core
## Puppet for cases where Puppet Server decides that it should be in charge of
## authorizing requests at the Clojure / Ring handler level - depending upon
## the configuration of the 'use_legacy_auth_conf' setting - and not core
## Puppet.

class Puppet::Server::AuthConfig
  def initialize
    Puppet.debug 'Using PuppetServer AuthConfig for master routes'
  end

  def check_authorization(method, path, params)
  end
end
