require 'puppet/server/config'

describe Puppet::Server::Config do
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
