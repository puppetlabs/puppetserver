require 'puppet/server'
require 'java'

java_import org.slf4j.LoggerFactory

Puppet::Util::Log.newdesttype :logback do
  def handle(msg)
    logger = LoggerFactory.getLogger("puppet-server")
    case msg.level
      when :debug
        logger.debug(msg.message)
      when :info, :notice
        logger.info(msg.message)
      when :warning
        logger.warn(msg.message)
      when :err, :alert, :emerg, :crit
        logger.error(msg.message)
    end
  end
end

class Puppet::Server::Logger
  def self.init_logging
    # Crank Puppet's log level all the way up and just control it via logback.
    Puppet::Util::Log.level = :debug

    Puppet::Util::Log.newdestination(:logback)
  end
end
