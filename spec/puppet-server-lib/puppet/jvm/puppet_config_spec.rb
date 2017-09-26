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

    describe '(PUP-6060) Puppet::Node indirection caching' do
      subject { Puppet[:node_cache_terminus] }
      it 'is nil to avoid superfluous caching' do
        expect(subject).to be_nil
      end

      subject { Puppet::Node.indirection.cache_class }
      it 'is nil to avoid superfluous caching'do
        expect(subject).to be_nil
      end
    end
  end

  # Even though we don't set the node_cache_terminus setting value, so it
  # defaults to nil, we want to honor it if users have specified it directly.
  # PUP-6060 / SERVER-1819
  subject { Puppet::Node.indirection.cache_class }
  it 'honors the Puppet[:node_cache_terminus] setting' do
    Puppet::Server::PuppetConfig.initialize_puppet({ :node_cache_terminus => "plain" })
    expect(subject).to eq(:plain)
  end
end
