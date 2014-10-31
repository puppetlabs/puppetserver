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

  context "execution stub" do
    it "should return an instance of ProcessOutput" do
      result = Puppet::Server::Config.execute("echo hi")
      expect(result).to be_a Puppet::Util::Execution::ProcessOutput
    end

    it "should return the STDOUT of the process" do
      result = Puppet::Server::Config.execute("echo hi")
      expect(result).to eq "hi\n"
    end

    it "should return the exit code of the process" do
      result = Puppet::Server::Config.execute("echo hi")
      expect(result.exitstatus).to eq 0

      result = Puppet::Server::Config.execute("false")
      expect(result.exitstatus).not_to eq 0
    end
  end

end
