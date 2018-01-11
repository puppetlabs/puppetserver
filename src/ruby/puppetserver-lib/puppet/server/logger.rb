require 'puppet/server'
require 'java'

java_import org.slf4j.LoggerFactory

Puppet::Util::Log.newdesttype :logback do
  def handle(msg)
    output = msg.to_s
    if msg.source.size > 0
      output = "#{msg.source} #{output}"
    end

    logger = LoggerFactory.getLogger("puppetserver")
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

  # @note This must be called after Puppet settings are configured; otherwise
  #   the default log level will overwrite this setting.
  def self.set_log_level_from_logback
    Puppet[:log_level] = level_from_logback(get_logger)
  end

  def self.level_from_logback(logger)
    case
    when logger.isDebugEnabled()
      'debug'
    when logger.isInfoEnabled()
      'info'
    when logger.isWarnEnabled()
      'warning'
    when logger.isErrorEnabled()
      'err'
    else
      'notice'
    end
  end

  def self.get_logger
    LoggerFactory.getLogger("puppetserver")
  end
end
