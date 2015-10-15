require 'puppet/server'

require 'puppet/network/http_pool'
require 'puppet/environments'

require 'puppet/server/logger'
require 'puppet/server/jvm_profiler'
require 'puppet/server/http_client'
require 'puppet/server/auth_config_loader'
require 'puppet/server/auth_provider'
require 'puppet/server/execution'
require 'puppet/server/environments/cached'

require 'java'
java_import com.puppetlabs.ssl_utils.SSLUtils
java_import java.io.FileReader

class Puppet::Server::Config

  def self.initialize_puppet_server(puppet_server_config)
    Puppet::Server::Logger.init_logging

    if puppet_server_config["profiler"]
      @profiler = Puppet::Server::JvmProfiler.new(puppet_server_config["profiler"])
      Puppet::Util::Profiler.add_profiler(@profiler)
    end

    Puppet::Server::HttpClient.initialize_settings(puppet_server_config)
    Puppet::Network::HttpPool.http_client_class = Puppet::Server::HttpClient

    if !puppet_server_config["use_legacy_auth_conf"]
      Puppet::Network::Authorization.authconfigloader_class =
          Puppet::Server::AuthConfigLoader
      Puppet::Network::AuthConfig.authprovider_class =
          Puppet::Server::AuthProvider
    end

    Puppet::Server::Execution.initialize_execution_stub

    if puppet_server_config["environment_registry"]
      Puppet::Environments::Cached.cache_expiration_service =
          Puppet::Server::Environments::Cached::CacheExpirationService.new(puppet_server_config["environment_registry"])
    end
  end

  def self.ssl_context
    # Initialize an SSLContext for use during HTTPS client requests.
    # Do this lazily due to startup-ordering issues - to give the CA
    # service time to create these files before they are referenced here.
    unless @ssl_context
      @ssl_context = SSLUtils.pems_to_ssl_context(
          FileReader.new(Puppet[:hostcert]),
          FileReader.new(Puppet[:hostprivkey]),
          FileReader.new(Puppet[:localcacert]))
    end
    @ssl_context
  end

  def self.terminate_puppet_server
    Puppet::Server::HttpClient.terminate
  end
end
