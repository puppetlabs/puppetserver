module Puppet
  module Server
    # Log to an array, just for testing.
    class LogCollector
      def initialize(logs)
        @logs = logs
      end

      def <<(value)
        @logs << value
      end
    end

    Puppet::Util::Log.newdesttype :collector do
      match "Puppet::Server::LogCollector"

      def initialize(messages)
        @messages = messages
      end

      def handle(msg)
        @messages << msg
      end
    end
  end
end
