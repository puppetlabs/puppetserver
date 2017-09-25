require 'puppet/server/config'

describe Puppet::Server::Config do
  context "initializing Puppet Server" do
    context "setting the Puppet log level from logback" do
      let(:logger) do
        stub("Logger", isDebugEnabled: false, isInfoEnabled: true, isWarnEnabled: false, isErrorEnabled: false)
      end

      before :each do
        Puppet::Server::Logger.expects(:get_logger).returns(logger)
        Puppet::Server::Config.initialize_puppet_server({})
      end

      it "the puppet log level (Puppet[:log_level]) is set from logback" do
        expect(Puppet[:log_level]).to eq('info')
      end

      it "the puppet log level (Puppet::Util::Log.level) is set from logback" do
        expect(Puppet::Util::Log.level).to eq(:info)
      end
    end
  end

  context "SSL context" do
    it "is configured with :localcacert and not :cacert" do
      Puppet[:hostcert] = "spec/fixtures/localhost-cert.pem"
      Puppet[:hostprivkey] = "spec/fixtures/localhost-privkey.pem"
      Puppet[:cacert] = "SHOULD/NOT/BE/USED"
      Puppet[:localcacert] = "spec/fixtures/ca-cert.pem"
      begin
        Puppet::Server::Config.ssl_context
      rescue Java::JavaIO::FileNotFoundException
        fail 'SSL context configured with :cacert'
      end
    end
  end
end
