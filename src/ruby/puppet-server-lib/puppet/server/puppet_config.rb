require 'puppet/server'

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

    if Puppet.settings.setting('always_retry_plugins')
      Puppet[:always_retry_plugins] = false
    else
      # (SERVER-410) Cache features in puppetserver for performance.  Avoiding
      # the cache is intended for agents to reload features mid-catalog-run.
      Puppet[:always_cache_features] = true
    end

    # Crank Puppet's log level all the way up and just control it via logback.
    Puppet[:log_level] = "debug"

    master_run_mode = Puppet::Util::RunMode[:master]
    app_defaults = Puppet::Settings.app_defaults_for_run_mode(master_run_mode).
        merge({:name => "master",
               :node_cache_terminus => :write_only_yaml,
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

  end
end
