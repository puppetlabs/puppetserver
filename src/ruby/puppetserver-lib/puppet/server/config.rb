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
java_import java.io.FileInputStream
java_import java.security.KeyStore

class Puppet::Server::Config
  PUPPET_KEYSTORE_LOCATION = '/opt/puppetlabs/puppet/ssl/puppet-cacerts'
  CERT_REGEX = /.*-----BEGIN CERTIFICATE-----.*/

  def self.initialize_puppet_server(puppet_server_config)
    Puppet::Server::Logger.init_logging

    if puppet_server_config["profiler"]
      @profiler = Puppet::Server::JvmProfiler.new(puppet_server_config["profiler"])
      Puppet::Util::Profiler.add_profiler(@profiler)
    end

    Puppet::Server::HttpClient.initialize_settings(puppet_server_config)

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

  def self.puppet_only_ssl_context
    # Initialize an SSLContext for use during HTTPS client requests.
    # Do this lazily due to startup-ordering issues - to give the CA
    # service time to create these files before they are referenced here.
    unless @puppet_only_ssl_context
      @puppet_only_ssl_context = SSLUtils.pems_to_ssl_context(
          FileReader.new(Puppet[:hostcert]),
          FileReader.new(Puppet[:hostprivkey]),
          FileReader.new(Puppet[:localcacert]))
    end
    @puppet_only_ssl_context
  end

  def self.ssl_context
    self.puppet_only_ssl_context
  end

  # The logging when loading these stores strives to match the equivalent
  # agent logging in 'puppet/ssl/ssl_provider'
  def self.puppet_and_system_ssl_context
    unless @puppet_and_system_ssl_context
      @puppet_and_system_ssl_context = load_puppet_and_system_ssl_context
    end

    @puppet_and_system_ssl_context
  end

  def self.load_puppet_and_system_ssl_context
    stores = SSLUtils.pemsToKeyAndTrustStores(
        FileReader.new(Puppet[:hostcert]),
        FileReader.new(Puppet[:hostprivkey]),
        FileReader.new(Puppet[:localcacert]))

    truststore = stores['truststore']
    if File.exist?(PUPPET_KEYSTORE_LOCATION)
      associate_entries(truststore, PUPPET_KEYSTORE_LOCATION)
    else
      Puppet.warning("Could not find Puppet-vendored keystore at '#{PUPPET_KEYSTORE_LOCATION}'")
    end

    if additional_store_location = Puppet[:ssl_trust_store]
      if File.exist?(additional_store_location)
        load_additional_store(truststore, additional_store_location)
      else
        Puppet.warning("The 'ssl_trust_store' setting does not refer to a file and will be ignored: '#{additional_store_location}'")
      end
    end

    SSLUtils.managerFactoriesToSSLContext(
      SSLUtils.getKeyManagerFactory(stores['keystore'], stores['keystore-pw']),
      SSLUtils.getTrustManagerFactory(stores['truststore']))
  end

  def self.load_additional_store(truststore, location)
    begin
      if File.read(location) =~ CERT_REGEX
        # The truststore looks like a cert chain the agent reads
        # Attempt to load it as SSLUtils would our own CA chain
        SSLUtils.associateCertsFromReader(
          truststore,
          'puppet_setting_ssl_trust_store',
          FileReader.new(location))
      else
        # Attempt to treat the file as a java keystore
        associate_entries(truststore, location)
      end
    rescue => detail
      Puppet.err("Failed to add '#{location}' as a trusted CA file: #{detail}")
    end
  end

  # to_store should be an already initialized Java KeyStore to act as a TrustStore
  # from_file should be a string path to a file that contains Java KeyStore formatted certs.
  def self.associate_entries(to_store, from_file)
    # We defer the creation of KeyStores to SSLUtils because it is FIPS specific.
    temp_truststore = SSLUtils.createKeyStore
    temp_truststore.load(FileInputStream.new(from_file), nil)
    temp_truststore.aliases.each do |a|
      to_store.setEntry(a, temp_truststore.getEntry(a, nil), nil)
    end
  end

  def self.terminate_puppet_server
    Puppet::Server::HttpClient.terminate
  end
end
