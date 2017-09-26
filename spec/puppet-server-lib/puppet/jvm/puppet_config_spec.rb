require 'puppet/server/puppet_config'

describe 'Puppet::Server::PuppetConfig' do
  context "initializing Puppet Server" do
    context "setting the Puppet log level from logback" do
      let(:logger) do
        stub("Logger", isDebugEnabled: false, isInfoEnabled: true, isWarnEnabled: false, isErrorEnabled: false)
      end

      before :each do
        Puppet::Server::Logger.expects(:get_logger).returns(logger)
        Puppet::Server::PuppetConfig.initialize_puppet({})
      end

      it "the puppet log level (Puppet[:log_level]) is set from logback" do
        expect(Puppet[:log_level]).to eq('info')
      end

      it "the puppet log level (Puppet::Util::Log.level) is set from logback" do
        expect(Puppet::Util::Log.level).to eq(:info)
      end
    end
  end

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
