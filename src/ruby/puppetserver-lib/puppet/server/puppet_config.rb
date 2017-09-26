require 'puppet/server'
require 'puppet/server/logger'

class Puppet::Server::PuppetConfig
  def self.initialize_puppet(puppet_config)
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
    Puppet.initialize_settings(
        puppet_config.reduce([]) do |acc, entry|
          acc << "--#{entry[0]}" << entry[1]
        end
    )
    Puppet[:trace] = true

    Puppet::Server::Logger.set_log_level_from_logback

    # (SERVER-410) Cache features in puppetserver for performance.  Avoiding
    # the cache is intended for agents to reload features mid-catalog-run.
    # As of (PUP-5482) setting always_retry_plugins to false implies that
    # features will always be cached.
    if Puppet.settings.setting('always_retry_plugins')
      Puppet[:always_retry_plugins] = false
    end

    master_run_mode = Puppet::Util::RunMode[:master]
    app_defaults = Puppet::Settings.app_defaults_for_run_mode(master_run_mode).
        merge({:name => "master",
               :facts_terminus => 'yaml'})
    Puppet.settings.initialize_app_defaults(app_defaults)

    Puppet.info("Puppet settings initialized; run mode: #{Puppet.run_mode.name}")

    Puppet::ApplicationSupport.push_application_context(master_run_mode)

    Puppet.settings.use :main, :master, :ssl, :metrics

    Puppet::FileServing::Content.indirection.terminus_class = :file_server
    Puppet::FileServing::Metadata.indirection.terminus_class = :file_server
    Puppet::FileBucket::File.indirection.terminus_class = :file

    Puppet::Node.indirection.cache_class = Puppet[:node_cache_terminus]

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
