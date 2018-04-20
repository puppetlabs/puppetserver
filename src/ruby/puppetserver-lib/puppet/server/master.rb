require 'puppet/server'

require 'puppet/info_service'

require 'puppet/network/http'
require 'puppet/network/http/api/master/v3'

require 'puppet/server/config'
require 'puppet/server/puppet_config'
require 'puppet/server/network/http/handler'

require 'java'

##
## This class is a bridge between the puppet ruby code and the java interface
## `com.puppetlabs.puppetserver.JRubyPuppet`.  The first `include` line in the class
## is some JRuby magic that causes this class to "implement" the Java interface.
## So, in this class we can make calls into the puppet ruby code, but from
## outside (in the clojure/Java code), we can interact with an instance of this
## class simply as if it were an instance of the Java interface; thus, consuming
## code need not be aware of any of the JRuby implementation details.
##
class Puppet::Server::Master
  include Java::com.puppetlabs.puppetserver.JRubyPuppet
  include Puppet::Server::Network::HTTP::Handler

  def initialize(puppet_config, puppet_server_config)
    Puppet::Server::Config.initialize_puppet_server(puppet_server_config)
    Puppet::Server::PuppetConfig.initialize_puppet(puppet_config)
    # Tell Puppet's network layer which routes we are willing handle - which is
    # the master routes, not the CA routes.
    master_prefix = Regexp.new("^#{Puppet::Network::HTTP::MASTER_URL_PREFIX}/")
    master_routes = Puppet::Network::HTTP::Route.path(master_prefix).
                          any.
                          chain(Puppet::Network::HTTP::API::Master::V3.routes)
    register([master_routes])
    @env_loader = Puppet.lookup(:environments)
  end

  def handleRequest(request)
    response = {}
    process(request, response)
    # 'process' returns only the status -
    # `response` now contains all of the response data

    body = response[:body]
    body_to_return =
        if body.is_a? String or body.nil?
          body
        elsif body.is_a? IO
          body.to_inputstream
        else
          raise "Don't know how to handle response body from puppet, which is a #{body.class}"
        end

    com.puppetlabs.puppetserver.JRubyPuppetResponse.new(
        response[:status],
        body_to_return,
        response[:content_type],
        response["X-Puppet-Version"])
  end

  def getClassInfoForEnvironment(env)
    environment = @env_loader.get(env)
    unless environment.nil?
      environments = Hash[env, self.class.getManifests(environment)]
      classes_per_env =
          Puppet::InfoService::ClassInformationService.new.classes_per_environment(environments)
      classes_per_env[env]
    end
  end

  def getModuleInfoForEnvironment(env)
    environment = @env_loader.get(env)
    unless environment.nil?
      self.class.getModules(environment)
    end
  end

  def getModuleInfoForAllEnvironments()
    all_envs = @env_loader.list
    all_mod_data = {}
    all_envs.each { |env|
      all_mod_data[env.name] = self.class.getModules(env)
    }
    all_mod_data
  end

  def getSetting(setting)
    Puppet[setting]
  end

  def puppetVersion()
    Puppet.version
  end

  def run_mode()
    Puppet.run_mode.name.to_s
  end

  def terminate
    Puppet::Server::Config.terminate_puppet_server
  end

   # @return [Array, nil] an array of hashes describing tasks
  def getTasks(env)
    environment = @env_loader.get(env)
    unless environment.nil?
      # Pass the original env string. environment.name is a symbol
      # while the environment cache is primarily used with strings.
      # Pass as a string to ensure we re-use a cached environment
      # if available.
      Puppet::InfoService.tasks_per_environment(env)
    end
  end

  def getTaskData(environment_name, module_name, task_name)
    # the 'init' task is special-cased to be just the name of the module,
    # otherwise we have to request 'module::taskname'
    qualified_task_name = task_name == 'init' ? module_name : "#{module_name}::#{task_name}"
    Puppet::InfoService.task_data(environment_name, module_name, qualified_task_name)
  end

  private

  def self.getModules(env)
    env.modules.collect do |mod|
      # NOTE: If in the future we want to collect more
      #       Module settings, this should be more programatic
      #       rather than getting these settings one by one
      {:name    => mod.forge_name ||= mod.name,
       :version => mod.version}
    end
  end

  def self.getManifests(env)
    manifests =
      case
        when env.manifest == Puppet::Node::Environment::NO_MANIFEST
          []
        when File.directory?(env.manifest)
          Dir.glob(File.join(env.manifest, '**/*.pp'))
        when File.exists?(env.manifest)
          [env.manifest]
        else
          []
      end

    module_manifests = env.modules.collect {|mod| mod.all_manifests}
    manifests.concat(module_manifests).flatten.uniq
  end
end
