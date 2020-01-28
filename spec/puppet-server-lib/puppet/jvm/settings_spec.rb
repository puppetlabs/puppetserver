require 'spec_helper'

require 'puppet/server/settings'

describe 'Puppet::Server::Settings' do

  before :each do
    @old_settings = Puppet.settings
    Puppet.replace_settings_object(Puppet::Settings.new)
    Puppet.initialize_default_settings!(Puppet.settings)


    @new_max_errors = Puppet[:max_errors] + 10
    puppet_config = {max_errors: @new_max_errors.to_s}
    Puppet::Server::PuppetConfig.initialize_puppet(puppet_config: puppet_config)

    Puppet.replace_settings_object(Puppet::Server::Settings.new(global_settings: Puppet.settings,
                                                                puppet_config: puppet_config))
  end

  after :each do
    Puppet.replace_settings_object(@old_settings)
  end

  it 'settings configured prior to replacement are kept in new threads' do
    thread = Thread.new do
      expect(Puppet[:max_errors]).to be(@new_max_errors)
    end
    thread.join
  end

  it 'does not write settings to the global config when set in threads' do
    thread = Thread.new do
      Puppet[:max_errors] = 0
    end
    thread.join
    expect(Puppet[:max_errors]).to be(@new_max_errors)
  end
end
