require 'puppet/server/environments'
require 'puppet/environments'

module Puppet::Server::Environments::Cached
  class CacheExpirationService
    def initialize(environment_registry)
      @environment_registry = environment_registry
    end

    def created(env)
      # `env` should be of type Puppet::Node::Environment, so we
      # can access properties like `modulepath` if we ever need to
      @environment_registry.register_environment(env.name)
    end

    def expired?(env_name)
      @environment_registry.is_expired?(env_name)
    end

    def evicted(env_name)
      @environment_registry.remove_environment(env_name)
    end
  end
end