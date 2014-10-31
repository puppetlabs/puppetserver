require 'puppet/server'

class Puppet::Server::Execution
  def self.initialize_execution_stub
    Puppet::Util::ExecutionStub.set do |command, options, stdin, stdout, stderr|
      if command.is_a?(Array)
        command = command.join(" ")
      end

      # TODO - options is currently ignored - https://tickets.puppetlabs.com/browse/SERVER-74

      # We're going to handle STDIN/STDOUT/STDERR in java, so we don't need
      # them here.  However, Puppet::Util::Execution.execute doesn't close them
      # for us, so we have to do that now.
      [stdin, stdout, stderr].each { |io| io.close rescue nil }

      execute command
    end
  end

  def self.execute(command)
    result = ExecutionStubImpl.executeCommand(command)
    Puppet::Util::Execution::ProcessOutput.new(result.getOutput, result.getExitCode)
  end
end