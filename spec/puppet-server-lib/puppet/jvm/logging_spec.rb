require 'spec_helper'

require 'puppet/server/puppet_config'
require 'puppet/server/logging'

describe Puppet::Server::Logging do
  context 'when setting the log level' do
    it 'flush logging queue' do
      # The logger will queue any old log messages, creating a logging destination
      # will force the pending queue to be flushed to this logger. We don't care
      # about these messages so we discard the logger, but we do not want them to
      # interfere with the next tests.
      _, _ = Puppet::Server::Logging.capture_logs('debug') do; end
    end

    it 'correctly filters messages' do
      _, logs = Puppet::Server::Logging.capture_logs('err') do
        Puppet.debug "Debug"
        Puppet.warning "Warning"
        Puppet.err "Error"
      end
      expect(logs.length).to eq(1)
      expect(logs[0]['level']).to eq("err")
    end

    it 'returns more verbose logging when requested' do
      _, logs = Puppet::Server::Logging.capture_logs('debug') do
        Puppet.debug "Debug"
        Puppet.warning "Warning"
        Puppet.err "Error"
      end
      expect(logs.length).to eq(3)
    end
  end
end
