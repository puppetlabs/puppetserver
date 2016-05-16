## This is a "dummy" AuthProvider that Puppet Server registers with core
## Puppet for cases where Puppet Server decides that it should be in charge of
## authorizing requests at the Clojure / Ring handler level - depending upon
## the configuration of the 'use_legacy_auth_conf' setting - and not core
## Puppet.

class Puppet::Server::AuthProvider
  def initialize(rights)
  end

  def check_authorization(method, path, params)
  end
end
