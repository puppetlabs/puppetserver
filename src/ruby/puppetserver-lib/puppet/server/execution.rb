require 'puppet/server'

require 'java'
java_import com.puppetlabs.puppetserver.ShellUtils

class Puppet::Server::Execution
  def self.initialize_execution_stub
    Puppet::Util::ExecutionStub.set do |command, options, stdin, stdout, stderr|
      # We're going to handle STDIN/STDOUT/STDERR in java, so we don't need
      # them here.  However, Puppet::Util::Execution.execute doesn't close them
      # for us, so we have to do that now.
      [stdin, stdout, stderr].each { |io| io.close rescue nil }

      execute(command, options)
    end
  end

  private
  # In ruby 1.9 (which jruby 1.7 runs as, `chars` returns an enumerator
  # [https://ruby-doc.org/core-1.9.3/String.html#method-i-chars], which is
  # unsuitable for passing to `Regexp.union`, so we explicitly turn it into an
  # array. In ruby 2.0 and later, `chars` returns an array
  # [https://ruby-doc.org/core-2.0.0/String.html#method-i-chars], so the `to_a`
  # call will essentially be a noop.
  SHELL_CHARACTERS = "*?{}[]<>()~&|\\$;'`\"\n#=".chars.to_a

  def self.execute(command, options)
    if command.is_a?(Array)
      orig_command_str = command.join(" ")
      binary = command.first
      args = command[1..-1]
    else
      orig_command_str = command
      binary = command
      args = nil
    end

    exe_options = ShellUtils::ExecutionOptions.new
    if options[:combine]
      exe_options.combine_stdout_stderr = true
    end

    if args && !args.empty?
      result = ShellUtils.executeCommand(binary, args.to_java(:string), exe_options)
    else
      if binary.index(Regexp.union(SHELL_CHARACTERS))
        result = ShellUtils.executeCommand("/bin/sh", ["-c", binary].to_java(:string), exe_options)
      else
        result = ShellUtils.executeCommand(binary, exe_options)
      end
    end

    # TODO - not all options from Puppet::Util::Execution are supported yet, see SERVER-74

    if options[:failonfail] and result.exit_code != 0
      raise Puppet::ExecutionFailure, "Execution of '#{orig_command_str}' returned #{result.exit_code}: #{result.output.strip}"
    end

    Puppet::Util::Execution::ProcessOutput.new(result.output, result.exit_code)
  end
end
