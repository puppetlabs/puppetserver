require 'spec_helper'

require 'puppet/server/config'

describe Puppet::Server::Config do
  context "SSL context" do
    before :each do
      Puppet[:hostcert] = "spec/fixtures/localhost-cert.pem"
      Puppet[:hostprivkey] = "spec/fixtures/localhost-privkey.pem"
      Puppet[:localcacert] = "spec/fixtures/ca-cert.pem"

      # The class will memoize loaded contexts in these variables
      Puppet::Server::Config.instance_variable_set(:@puppet_and_system_ssl_context, nil)
      Puppet::Server::Config.instance_variable_set(:@puppet_only_ssl_context, nil)
    end

    it "is configured with :localcacert and not :cacert" do
      Puppet[:cacert] = "SHOULD/NOT/BE/USED"

      begin
        Puppet::Server::Config.ssl_context
      rescue Java::JavaIO::FileNotFoundException
        fail 'SSL context configured with :cacert'
      end
    end

    it "ssl_context and puppet_only_ssl_context are the same SSLContext" do
      ssl_context = Puppet::Server::Config.ssl_context
      puppet_only_ssl_context = Puppet::Server::Config.puppet_only_ssl_context

      expect(ssl_context).to be_a_kind_of(Java::JavaxNetSsl::SSLContext)
      expect(ssl_context).to eq(puppet_only_ssl_context)
    end

    it "loads the puppet certs and any existing configured system certs w/o error" do
      Puppet[:ssl_trust_store] = "spec/fixtures/puppet-cacerts"
      begin
        ssl_context = Puppet::Server::Config.puppet_and_system_ssl_context
        expect(ssl_context).to be_a_kind_of(Java::JavaxNetSsl::SSLContext)
      rescue => e
        fail "Could not initialize puppet_and_system_ssl_context because of #{e.inspect}"
      end
    end

    it "warns if :ssl_trust_store is set but not readable" do
      Puppet[:ssl_trust_store] = "spec/fixtures/foo.pem"
      allow(File).to receive(:exist?).and_return(false)

      expect(Puppet).to receive(:warning).with(/Could not find Puppet-vendored keystore/)
      expect(Puppet).to receive(:warning).with(/The 'ssl_trust_store' setting does not refer to a file/)

      Puppet::Server::Config.puppet_and_system_ssl_context
    end
  end
end
