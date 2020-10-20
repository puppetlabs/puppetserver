require 'puppet/server'
require 'puppet/server/logger'
require 'puppet/server/http_client'

class Puppet::Server::PuppetConfig

  # Configure the Puppet.settings object
  # @param puppet_config [Hash] Hash of puppet settings
  # @param require_config [Boolean] controls loading of Puppet configuration files
  # @param push_global_settings [Boolean] controls push to global context after settings object initialization
  # @return [void]
  def self.initialize_puppet_settings(puppet_config:, require_config:, push_settings_globally:)

    # It's critical that we initialize the run mode before allowing any of the
    # other settings to be loaded / accessed.
    Puppet.settings.preferred_run_mode = :master

    # Puppet.initialize_settings is the method that you call if you want to use
    # the puppet code as a library.  (It is called implicitly by all of the puppet
    # cli tools.)  Here we can basically pass through any settings that we wish
    # to modify/override in the same syntax as you would pass in cli args to
    # set them.
    #
    # `config` is a map whose keys are the names of the settings that we wish
    # to override, and whose values are the desired values for the settings.
    # Values that are not strings are omitted, allowing for keys in the
    # HashMap from puppetserver to have true values.
    cli_flags = puppet_config.reduce([]) do |acc, entry|
      acc << "--#{entry[0]}"
      acc << entry[1] if entry[1].kind_of?(String)
      acc
    end

    # We check parameter length because Method#arity returns -1 for varargs
    if Puppet.method(:initialize_settings).parameters.length >= 3
      Puppet.initialize_settings(cli_flags, require_config, push_settings_globally,
                                 { :http => Puppet::Server::HttpClient.new })
    else
      Puppet.initialize_settings(cli_flags)
    end

    Puppet[:trace] = true

    Puppet::Server::Logger.set_log_level_from_logback

    # (SERVER-410) Cache features in puppetserver for performance.  Avoiding
    # the cache is intended for agents to reload features mid-catalog-run.
    # As of (PUP-5482) setting always_retry_plugins to false implies that
    # features will always be cached.
    if Puppet.settings.setting('always_retry_plugins')
      Puppet[:always_retry_plugins] = false
    end

    app_defaults = Puppet::Settings.app_defaults_for_run_mode(Puppet::Util::RunMode[:master]).
        merge({:name => "master",
               :facts_terminus => 'yaml'})
    Puppet.settings.initialize_app_defaults(app_defaults)

    if push_settings_globally
      Puppet.info("Puppet settings initialized; run mode: #{Puppet.run_mode.name}")
    else
      Puppet.info("Puppet local settings initialized; run mode: #{Puppet.run_mode.name}")
    end

  end

  # This `initialize_puppet` method should only run during the initialization
  # of the Puppet::Server::Master class. Beyond the Puppet.settings configuration,
  # it configures additional state for Puppet to begin compilations.
  def self.initialize_puppet(puppet_config:)
    initialize_puppet_settings(puppet_config: puppet_config,
                               require_config: true,
                               push_settings_globally: true)

    Puppet::ApplicationSupport.push_application_context(Puppet::Util::RunMode[:master])

    # Puppet's https machinery expects to find an object at
    # `Puppet.lookup(:ssl_context)` which it will then use as an input
    # to the Verifier, which is passed to the client. We ignore these
    # values when passed to our https client and manage our SSLContext
    # in a completely different way.
    #
    # See `Puppet::Network::HttpPool.connection`
    dummy_ssl_context = {ssl_context: :unused}
    if Puppet.respond_to?(:push_context_global)
      Puppet.push_context_global(dummy_ssl_context)
    else
      Puppet.push_context(dummy_ssl_context)
    end

    Puppet.settings.use :main, :master, :ssl, :metrics

    if Puppet::Indirector::Indirection.method_defined?(:set_global_setting)
      Puppet::FileServing::Content.indirection.set_global_setting(:terminus_class, :file_server)
      Puppet::FileServing::Metadata.indirection.set_global_setting(:terminus_class, :file_server)
      Puppet::FileBucket::File.indirection.set_global_setting(:terminus_class, :file)
      Puppet::Node.indirection.set_global_setting(:cache_class, Puppet[:node_cache_terminus])
    else
      Puppet::FileServing::Content.indirection.terminus_class = :file_server
      Puppet::FileServing::Metadata.indirection.terminus_class = :file_server
      Puppet::FileBucket::File.indirection.terminus_class = :file
      Puppet::Node.indirection.cache_class = Puppet[:node_cache_terminus]
    end

    Puppet::ApplicationSupport.configure_indirector_routes("master")

    oid_defns = Puppet::SSL::Oids.parse_custom_oid_file(Puppet[:trusted_oid_mapping_file])
    if oid_defns
      @@oid_defns = Puppet::SSL::Oids::PUPPET_OIDS + oid_defns
    else
      @@oid_defns = Puppet::SSL::Oids::PUPPET_OIDS
    end
  end

  def self.oid_defns
    @@oid_defns
  end

end
