require 'spec_helper'

require 'puppet/server/execution'

def test_execute(command, options = {})
  # NOTE: the `execute` function is not part of the public API,
  #  but is still the best level of abstraction to use for this sort
  #  of test, so we're abusing Ruby's `send` for these tests.
  Puppet::Server::Execution.send(:execute,
                                 command, options)
end

describe Puppet::Server::Execution do
  context "execution stub" do
    it "should return an instance of ProcessOutput" do
      result = test_execute("echo hi")
      expect(result).to be_a Puppet::Util::Execution::ProcessOutput
    end

    it "should return the STDOUT of the process" do
      result = test_execute("echo hi")
      expect(result).to eq "hi\n"
    end

    it "should combine STDOUT/STDERR of the process when :combine is set" do
      result = test_execute("./spec/fixtures/puppet-server-lib/puppet/jvm/execution_spec/echo_stdout_and_stderr.sh",
                            {:combine => true})
      expect(result).to match(/hello stdout/)
      expect(result).to match(/hello stderr/)
    end

    it "should return an instance of ProcessOutput for a command with args" do
      result = test_execute(["echo", "hi"])
      expect(result).to be_a Puppet::Util::Execution::ProcessOutput
    end

    it "should return the STDOUT of the process for a command with args" do
      result = test_execute(["echo", "hi"])
      expect(result).to eq "hi\n"
    end

    it "returns an instance of ProcessOutput for a command with an empty array of args" do
      result = test_execute("echo hi")
      expect(result).to be_a Puppet::Util::Execution::ProcessOutput
      expect(result).to eq "hi\n"
    end

    it "should return the exit code of the process" do
      result = test_execute("echo hi")
      expect(result.exitstatus).to eq 0

      result = test_execute(["echo",  "hi"])
      expect(result.exitstatus).to eq 0

      result = test_execute("false")
      expect(result.exitstatus).not_to eq 0
    end

    context "it should support other shellisms" do
      it "should support pipes" do
        result = test_execute("echo foo | wc -c")
        expect(result).to be_a Puppet::Util::Execution::ProcessOutput
        expect(result.strip).to eq "4"
        expect(result.exitstatus).to eq 0
      end

      it "should support environment variables" do
        result = test_execute(%(FOO=bar python -c "import os; print os.environ['FOO']"))
        expect(result).to be_a Puppet::Util::Execution::ProcessOutput
        expect(result).to eq "bar\n"
        expect(result.exitstatus).to eq 0
      end

      it "should support environment newlines" do
        result = test_execute(%(echo "foo\nbar"))
        expect(result).to be_a Puppet::Util::Execution::ProcessOutput
        expect(result.lines.count).to eq 2
        expect(result.exitstatus).to eq 0
      end
    end


    it "should raise an error if `failonfail` is true and the process returns non-zero" do
      expect {
        test_execute("false", {:failonfail => true})
      }.to raise_error(Puppet::ExecutionFailure, /^Execution of 'false' returned 1/)
    end
  end
end
