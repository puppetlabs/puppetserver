## This is a "dummy" AuthConfigLoader that Puppet Server registers with core
## Puppet for cases where Puppet Server decides that it should be in charge of
## authorizing requests at the Clojure / Ring handler level - depending upon
## the configuration of the 'use_legacy_auth_conf' setting - and not core
## Puppet.

require 'puppet/server/auth_config'

class Puppet::Server::AuthConfigLoader
  def self.authconfig
    @cached_authconfig ||= Puppet::Server::AuthConfig.new
  end
end
