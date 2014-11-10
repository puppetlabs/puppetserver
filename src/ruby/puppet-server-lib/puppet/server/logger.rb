require 'puppet/server'
require 'java'

java_import org.slf4j.LoggerFactory

Puppet::Util::Log.newdesttype :logback do
  def handle(msg)
    output = msg.message
    if msg.source.size > 0
      output = "#{msg.source} #{output}"
    end

    logger = LoggerFactory.getLogger("puppet-server")
    case msg.level
      when :debug
        logger.debug(output)
      when :info, :notice
        logger.info(output)
      when :warning
        logger.warn(output)
      when :err, :alert, :emerg, :crit
        logger.error(output)
    end
  end
end

class Puppet::Server::Logger
  def self.init_logging
    Puppet::Util::Log.newdestination(:logback)
  end
end
