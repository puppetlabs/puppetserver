require 'forwardable'

class Puppet::Server::CompilerContext
  THREAD_LOCAL_SETTINGS = Set.new([:strict, :code, :tasks])

  def initialize(global_settings)
    @settings = {}
    THREAD_LOCAL_SETTINGS.each do |setting|
      @settings[setting] = global_settings[setting]
    end
  end

  def [](key)
    @settings[key]
  end
end

class Puppet::Server::Settings
  extend Forwardable
  THREAD_LOCAL_SETTINGS = Set.new([:strict, :code, :tasks])

  def initialize(global_settings, puppet_config)
    @global_settings = global_settings
    @local_settings = Puppet::ThreadLocal.new {nil}
    @puppet_config = puppet_config.reduce([]) do |acc, entry|
      acc << "--#{entry[0]}"
      acc << entry[1] if entry[1].kind_of?(String)
      acc
    end
    @compiler_context = Puppet::ThreadLocal.new(Puppet::Server::CompilerContext.new(global_settings))
  end

  def_delegators :current_settings, :preferred_run_mode, :clear_environment_settings, :values, :parse_file,
                 :value, :set_by_cli?, :app_defaults_initialized?, :each_key, :value_sym, :use, :define_settings,
                 :initialize_app_defaults, :initialize_global_settings

  def clear_local_settings
    @local_settings.value = nil
  end

  def generate_new_local_config
    # When assigning a new value for a setting, generate a new local_settings object
    # that overrides the global_settings. In order to configure the settings object
    # with the proper values, there is code duplicated here from the PuppetConfig
    # class and the initialization of settings from Puppet itself. It might be good
    # here to break up #PuppetConfig.initialize_settings into multiple parts, so that
    # this particular settings initialization can happen in one defined method.
    @local_settings.value = Puppet::Settings.new
    master_run_mode = Puppet::Util::RunMode[:master]
    app_defaults = Puppet::Settings.app_defaults_for_run_mode(master_run_mode).
                     merge({:name => "master",
                            :facts_terminus => 'yaml'})

    Puppet.load_default_settings(@local_settings.value)
    Puppet.settings.initialize_global_settings(@puppet_config, require_config = true)
    Puppet.settings.initialize_app_defaults(app_defaults)
  end

  def local_settings?
    !@local_settings.value.nil?
  end

  def current_settings
    local_settings? ? @local_settings.value : @global_settings
  end

  def [](key)
    if THREAD_LOCAL_SETTINGS.include?(key)
      @compiler_context.value[key]
    else
      current_settings[key]
    end
  end

  def []=(key, value)
    if THREAD_LOCAL_SETTINGS.include?(key)
      @compiler_context.value[key] = value
      return
    end
    if @local_settings.value.nil?
      generate_new_local_config
    end
    @local_settings.value[key] = value
  end
end
