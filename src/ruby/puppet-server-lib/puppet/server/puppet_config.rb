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

    # Crank Puppet's log level all the way up and just control it via logback.
    Puppet::Util::Log.level = :debug

    master_run_mode = Puppet::Util::RunMode[:master]
    app_defaults = Puppet::Settings.app_defaults_for_run_mode(master_run_mode).
        merge({:name => "master",
               :node_cache_terminus => :write_only_yaml,
               :facts_terminus => 'yaml'})
    Puppet.settings.initialize_app_defaults(app_defaults)

    Puppet.info("Puppet settings initialized; run mode: #{Puppet.run_mode.name}")

    reset_environment_context()

    Puppet.settings.use :main, :master, :ssl, :metrics

    Puppet::FileServing::Content.indirection.terminus_class = :file_server
    Puppet::FileServing::Metadata.indirection.terminus_class = :file_server
    Puppet::FileBucket::File.indirection.terminus_class = :file

    Puppet::Node.indirection.cache_class = Puppet[:node_cache_terminus]

    configure_indirector_routes()

  end

  private
  def self.reset_environment_context
    # The following lines were copied for the most part from the run() method
    # in the Puppet::Application class from .../lib/puppet/application.rb
    # in core Ruby Puppet code.  The logic in the Puppet::Application class is
    # executed by the core Ruby Puppet master during its initialization.
    #
    # The call to Puppet.base_context is needed in order for the modulepath
    # settings just implicitly reprocessed for master run mode to be
    # reset onto the Environment objects that later Ruby Puppet requests
    # will use (e.g., for agent pluginsyncs).
    #
    # It would be better for the logic below to be put in a location where
    # both the core Ruby Puppet and Puppet Server masters can use the same
    # implementation.  A separate ticket, PE-4356, was filed to cover this
    # follow-on work.

    Puppet.push_context(Puppet.base_context(Puppet.settings),
                        "Update for application settings (Puppet Server).")
    # This use of configured environment is correct, this is used to establish
    # the defaults for an application that does not override, or where an override
    # has not been made from the command line.
    #
    configured_environment_name = Puppet[:environment]
    configured_environment =
        Puppet.lookup(:environments).get(configured_environment_name)
    configured_environment =
        configured_environment.override_from_commandline(Puppet.settings)

    if configured_environment.nil?
      fail(Puppet::Environments::EnvironmentNotFound, configured_environment_name)
    end
    Puppet.push_context({:current_environment => configured_environment},
                        "Update current environment from puppet master's configuration")
  end

  def self.configure_indirector_routes
    # The following lines were copied for the most part from the
    # configure_indirector_routes() method in the Puppet::Application class from
    # .../lib/puppet/application.rb in core Ruby Puppet code.
    route_file = Puppet[:route_file]
    if Puppet::FileSystem.exist?(route_file)
      routes = YAML.load_file(route_file)
      application_routes = routes["master"]
      Puppet::Indirector.configure_routes(application_routes) if application_routes
    end
  end

end
