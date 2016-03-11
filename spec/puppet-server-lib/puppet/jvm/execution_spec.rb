require 'spec_helper'

require 'puppet/server/execution'

describe Puppet::Server::Execution do
  context "execution stub" do
    it "should return an instance of ProcessOutput" do
      result = Puppet::Server::Execution.execute("echo hi")
      expect(result).to be_a Puppet::Util::Execution::ProcessOutput
    end

    it "should return the STDOUT of the process" do
      result = Puppet::Server::Execution.execute("echo hi")
      expect(result).to eq "hi\n"
    end

    it "should return an instance of ProcessOutput for a command with args" do
      result = Puppet::Server::Execution.execute("echo",  ["hi"])
      expect(result).to be_a Puppet::Util::Execution::ProcessOutput
    end

    it "should return the STDOUT of the process for a command with args" do
      result = Puppet::Server::Execution.execute("echo", ["hi"])
      expect(result).to eq "hi\n"
    end

    it "should return the exit code of the process" do
      result = Puppet::Server::Execution.execute("echo hi")
      expect(result.exitstatus).to eq 0

      result = Puppet::Server::Execution.execute("echo",  ["hi"])
      expect(result.exitstatus).to eq 0

      result = Puppet::Server::Execution.execute("false")
      expect(result.exitstatus).not_to eq 0
    end
  end
end
