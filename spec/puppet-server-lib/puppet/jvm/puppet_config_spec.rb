require 'puppet/server/puppet_config'

describe 'Puppet::Server::PuppetConfig' do
  context "When puppet has had settings initialized" do
    before :each do
      Puppet::Server::PuppetConfig.initialize_puppet({})
    end

    describe '(PUP-5482) Puppet[:always_retry_plugins]' do
      subject { Puppet[:always_retry_plugins] }
      it 'is false for increased performance in puppetserver' do
        expect(subject).to eq(false)
      end
    end
  end
end
