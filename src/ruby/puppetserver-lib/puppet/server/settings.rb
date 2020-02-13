require 'puppet/server/puppet_config'

class Puppet::Server::Settings

  # @param global_settings[Puppet::Settings] read-only puppet settings shared globally
  # @param puppet_config[Hash] the setting options to use for new thread-local settings
  def initialize(global_settings:, puppet_config:)
    @global_settings = global_settings
    @local_settings = Puppet::ThreadLocal.new
    @puppet_config = puppet_config
  end

  def method_missing(method, *args, &block)
    current_settings.send(method, *args, &block)
  end

  def clear_local_settings
    @local_settings.value = nil
  end

  # This method populates the local_settings value, so that any reading of
  # settings will read from @local_settings.value instead of the @global_settings.
  # This should never happen on the parent thread, but on threads spawned
  # from the parent during compilation.
  def generate_new_local_settings
    @local_settings.value = Puppet::Settings.new
    Puppet.initialize_default_settings!(@local_settings.value)
    Puppet::Server::PuppetConfig.initialize_puppet_settings(puppet_config: @puppet_config,
                                                            require_config: true,
                                                            push_settings_globally: false)
  end

  def local_settings?
    !@local_settings.value.nil?
  end

  def current_settings
    local_settings? ? @local_settings.value : @global_settings
  end

  def [](key)
    current_settings[key]
  end

  def []=(key, value)
    if !local_settings?
      generate_new_local_settings
    end
    @local_settings.value[key] = value
  end

end
