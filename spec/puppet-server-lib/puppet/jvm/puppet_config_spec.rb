require 'puppet/server/puppet_config'

describe 'Puppet::Server::PuppetConfig' do
  context "When puppet has had settings initialized" do
    before :each do
      mock_puppet_config = {}
      Puppet::Server::PuppetConfig.initialize_puppet(mock_puppet_config)
    end

    describe "the puppet log level (Puppet[:log_level])" do
      subject { Puppet[:log_level] }
      it 'is set to debug (the highest) so messages make it to logback' do
        expect(subject).to eq('debug')
      end
    end

    describe "the puppet log level (Puppet::Util::Log.level)" do
      subject { Puppet::Util::Log.level }
      it 'is set to debug (the highest) so messages make it to logback' do
        expect(subject).to eq(:debug)
      end
    end

    describe '(SERVER-410) Puppet[:always_cache_features]' do
      subject { Puppet[:always_cache_features] }
      it 'is true for increased performance in puppet-server' do
        expect(subject).to eq(true)
      end
    end

    describe '(PUP-5482) Puppet[:always_retry_plugins]' do
      subject { Puppet[:always_retry_plugins] }
      it 'is false for increased performance in puppet-server' do
        expect(subject).to eq(false)
      end
    end
  end
end
