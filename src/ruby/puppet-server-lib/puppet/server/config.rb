require 'puppet/server'

require 'puppet/network/http_pool'

require 'puppet/server/jvm_profiler'
require 'puppet/server/http_client'
require 'puppet/server/logger'
require 'puppet/server/execution'

require 'java'
java_import com.puppetlabs.certificate_authority.CertificateAuthority
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

    Puppet::Server::Execution.initialize_execution_stub
  end

  def self.ssl_context
    # Initialize an SSLContext for use during HTTPS client requests.
    # Do this lazily due to startup-ordering issues - to give the CA
    # service time to create these files before they are referenced here.
    unless @ssl_context
      @ssl_context = CertificateAuthority.pems_to_ssl_context(
          FileReader.new(Puppet[:hostcert]),
          FileReader.new(Puppet[:hostprivkey]),
          FileReader.new(Puppet[:localcacert]))
    end
    @ssl_context
  end
end
