require 'puppet/server/log_collector'

module Puppet
  module Server
    module Logging

      # @param level [String] one of "err", "warning", "info", or "debug",
      #                       overriding the log level of the underlying
      #                       server process. If nil, the process's log level
      #                       will be used.
      # @return the result of the block, along with the list of log entries
      #         that occurred while processing the block
      def self.capture_logs(level, &block)
        old_log_level = Puppet.settings[:log_level]
        Puppet.settings[:log_level] = level if level

        logs = []
        result = nil
        log_dest = Puppet::Server::LogCollector.new(logs)
        Puppet::Util::Log.with_destination(log_dest) do
          result = yield
        end

        Puppet.settings[:log_level] = old_log_level

        return result, logs
      end
    end
  end
end
