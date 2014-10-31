require 'puppet'
require 'puppet/server'
require 'puppet/server/jvm_profiler'
require 'puppet/server/http_client'

require 'java'
java_import com.puppetlabs.certificate_authority.CertificateAuthority
java_import java.io.FileReader

class Puppet::Server::Config

  def self.initialize_puppet_server(puppet_server_config)
    if puppet_server_config.has_key?("profiler")
      @profiler = Puppet::Server::JvmProfiler.new(puppet_server_config["profiler"])

    end
    Puppet::Server::HttpClient.initialize_settings(puppet_server_config)
    Puppet::Network::HttpPool.http_client_class = Puppet::Server::HttpClient
    if @profiler
      Puppet::Util::Profiler.add_profiler(@profiler)
    end

    Puppet::Server::Logger.init_logging
    initialize_execution_stub
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

  private

  def self.initialize_execution_stub
    Puppet::Util::ExecutionStub.set do |command, options, stdin, stdout, stderr|
      if command.is_a?(Array)
        command = command.join(" ")
      end

      # TODO - options is currently ignored - https://tickets.puppetlabs.com/browse/SERVER-74

      # We're going to handle STDIN/STDOUT/STDERR in java, so we don't need
      # them here.  However, Puppet::Util::Execution.execute doesn't close them
      # for us, so we have to do that now.
      [stdin, stdout, stderr].each { |io| io.close rescue nil }

      execute command
    end
  end

  def self.execute(command)
    result = ExecutionStubImpl.executeCommand(command)
    Puppet::Util::Execution::ProcessOutput.new(result.getOutput, result.getExitCode)
  end

end
