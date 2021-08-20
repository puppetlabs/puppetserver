require 'puppet/server'

require 'puppet/info_service'

require 'puppet/network/http'
require 'puppet/network/http/api/master/v3'
require 'puppet/node/facts'

require 'puppet/server/config'
require 'puppet/server/puppet_config'
require 'puppet/server/network/http/handler'
require 'puppet/server/compiler'
require 'puppet/server/ast_compiler'
require 'puppet/server/key_recorder'
require 'puppet/server/settings'
require 'java'
require 'timeout'

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
    # There is a setting that is routed from the puppetserver.conf to
    # configure whether or not to track hiera lookups.
    @track_lookups = puppet_server_config.delete('track_lookups')
    multithreaded = puppet_server_config.delete('multithreaded')
    Puppet::Server::Config.initialize_puppet_server(puppet_server_config)
    Puppet::Server::PuppetConfig.initialize_puppet(puppet_config: puppet_config)
    # Tell Puppet's network layer which routes we are willing handle - which is
    # the master routes, not the CA routes.
    master_prefix = Regexp.new("^#{Puppet::Network::HTTP::MASTER_URL_PREFIX}/")
    master_routes = Puppet::Network::HTTP::Route.path(master_prefix).
                          any.
                          chain(Puppet::Network::HTTP::API::Master::V3.routes)
    register([master_routes])
    @env_loader = Puppet.lookup(:environments)
    @transports_loader = Puppet::Util::Autoload.new(self, "puppet/transport/schema")
    @catalog_compiler = Puppet::Server::Compiler.new

    if multithreaded
      if Puppet.respond_to?(:replace_settings_object)
        Puppet.replace_settings_object(Puppet::Server::Settings.new(global_settings: Puppet.settings,
                                                                    puppet_config: puppet_config))
      else
        Puppet.warning(
          "Attempting to run in multithreaded mode without a version of Puppet that " +
          "supports threadsafe settings. Please upgrade to Puppet 6.13.0 or greater.")
      end
    end
  end

  def handleRequest(request)
    response = {}
    Puppet.override(lookup_key_recorder: create_recorder) do
      process(request, response)
      # 'process' returns only the status -
      # `response` now contains all of the response data
    end

    body = response[:body]
    body_to_return =
        if body.is_a? String
          if body.encoding == Encoding::ASCII_8BIT
            body.to_java_bytes
          else
            body
          end
        elsif body.is_a? IO
          body.to_inputstream
        elsif body.nil?
          body
        else
          raise "Don't know how to handle response body from puppet, which is a #{body.class}"
        end

    com.puppetlabs.puppetserver.JRubyPuppetResponse.new(
        response[:status],
        body_to_return,
        response[:content_type],
        response["X-Puppet-Version"])
  ensure
    Puppet.settings.clear_local_settings if Puppet.settings.is_a?(Puppet::Server::Settings)
  end

  def compileCatalog(request_data)
    Puppet.override(lookup_key_recorder: create_recorder) do
      @catalog_compiler.compile(convert_java_args_to_ruby(request_data))
    end
  end

  def compileAST(compile_options, boltlib_path)
    ruby_compile_options = convert_java_args_to_ruby(compile_options)
    ruby_boltlib_path = boltlib_path.java_kind_of?(Java::JavaUtil::List) ? boltlib_path.to_a : nil
    Puppet::Server::ASTCompiler.compile(ruby_compile_options, ruby_boltlib_path)
  end

  def create_recorder
    @track_lookups ? Puppet::Server::KeyRecorder.new : Puppet::Pops::Lookup::KeyRecorder.singleton
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

  def getTransportInfoForEnvironment(env)
    require 'puppet/resource_api/transport'
    Puppet::ResourceApi::Transport.list_all_transports(env).values.map do |transport|
      definition = transport.definition
      order_info = definition[:connection_info_order]
      definition[:connection_info_order] = order_info.map(&:to_s)
      definition
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
    # executor shutdown can be removed after puppetserver upgrades to a version of jruby
    # following merge of https://github.com/jruby/jruby/pull/6127
    executor = Timeout.to_java.getInternalVariable('__executor__')
    executor.shutdown
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

  def getPlans(env)
    environment = @env_loader.get(env)
    unless environment.nil?
      # Pass the original env string. environment.name is a symbol
      # while the environment cache is primarily used with strings.
      # Pass as a string to ensure we re-use a cached environment
      # if available.
      Puppet::InfoService.plans_per_environment(env)
    end
  end

  def getPlanData(environment_name, module_name, plan_name)
    # the 'init' plan is special-cased to be just the name of the module,
    # otherwise we have to request 'module::planname'
    qualified_plan_name = plan_name == 'init' ? module_name : "#{module_name}::#{plan_name}"
    Puppet::InfoService.plan_data(environment_name, module_name, qualified_plan_name)
  end

  private

  # This helper is used to resolve all java objects in an array.
  # Each array element is examined, if it is expected to be a map
  # we call back to the convert_java_args_to_ruby method, if it
  # is expected to be an array, we recurse otherwise we do not modify
  # the value. 
  def resolve_java_objects_from_list(list)
    list.map do |value|
      if value.java_kind_of?(Java::ClojureLang::IPersistentMap)
        convert_java_args_to_ruby(value)
      elsif value.java_kind_of?(Java::JavaUtil::List)
        resolve_java_objects_from_list(value)
      else
        value
      end
    end
  end

  def convert_java_args_to_ruby(hash)
    Hash[hash.collect do |key, value|
      # Stolen and heavily modified from params_to_ruby in handler.rb
      if value.java_kind_of?(Java::ClojureLang::IPersistentMap)
        [key, convert_java_args_to_ruby(value)]
      elsif value.java_kind_of?(Java::JavaUtil::List)
        [key, resolve_java_objects_from_list(value)]
      else
        [key, value]
      end
    end]
  end

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
