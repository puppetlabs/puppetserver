require 'puppet/server'

require 'puppet/info_service'

require 'puppet/network/http'
require 'puppet/network/http/api/master/v3'
require 'puppet/node/facts'

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
    set_server_facts
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

  def convert_java_args_to_ruby(hash)
    Hash[hash.collect do |key, value|
        # Stolen and modified from params_to_ruby in handler.rb
        newkey = key.to_s
        newkey.slice!(0)
        if value.java_kind_of?(Java::ClojureLang::PersistentArrayMap)
          [newkey, convert_java_args_to_ruby(value)]
        else
          [newkey, value.java_kind_of?(Java::JavaUtil::List) ? value.to_a : value]
        end
      end]
  end

  def compileCatalog(request_data)
    processed_hash = convert_java_args_to_ruby(request_data)

    facts, trusted_facts = process_facts(processed_hash)
    node_params = { facts: facts,
                    environment: processed_hash['environment'],
                    # Are these 'parameters' the same as what Node expects?
                    # There's a bunch of code in Node around merging additional things,
                    # notably facts, into the 'parameter' field. Is that necessary? If so,
                    # why?
                    parameters: processed_hash['parameters'],
                    classes: processed_hash['classes'] }

    node = Puppet::Node.new(processed_hash['certname'], node_params)
    node.trusted_data = trusted_facts
    node.add_server_facts(@server_facts)
    catalog = Puppet::Parser::Compiler.compile(node, processed_hash['job_id'])
    catalog.to_data_hash
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

  # @return Puppet::Node::Facts facts, Hash trusted_facts
  def process_facts(request_data)
    if request_data['facts'].nil?
      facts = get_facts_from_pdb(request_data['certname'], request_data['environment'])
    else
      facts_from_request = request_data['facts']
      facts_from_request['name'] = request_data['certname']
      facts = Puppet::Node::Facts.from_data_hash(facts_from_request)
    end

    fact_values = if facts.nil?
                    {}
                  else
                    facts.sanitize
                    facts.to_data_hash
                  end

    # Pull the trusted facts from the request, or attempt to extract them from
    # the facts hash
    trusted_facts = if request_data['trusted_facts'].nil?
                      fact_values['trusted'].nil? ? {} : fact_values['trusted']
                    else
                      request_data['trusted_facts']['values']
                    end

    return facts, trusted_facts
  end

  def get_facts_from_pdb(nodename, environment)
    if Puppet[:storeconfigs_backend] == :puppetdb
      Puppet::Node::Facts.indirection.find(nodename, :environment => environment)
    else
      # How should this be surfaced? Seems like we could maybe do better than a 500, unless
      # that's accurate?
      raise(Puppet::Error, "PuppetDB not configured, please provide facts with your catalog request.")
    end
  end

  # Initialize our server fact hash; we add these to each client, and they
  # won't change while we're running, so it's safe to cache the values.
  def set_server_facts
    @server_facts = {}

    # Add our server version to the fact list
    @server_facts['serverversion'] = Puppet.version.to_s

    # And then add the server name and IP
    { 'servername' => 'fqdn',
      'serverip' => 'ipaddress'
    }.each do |var, fact|
      if value = Facter.value(fact)
        @server_facts[var] = value
      else
        Puppet.warning "Could not retrieve fact #{fact}"
      end
    end

    if @server_facts['servername'].nil?
      host = Facter.value(:hostname)
      if domain = Facter.value(:domain)
        @server_facts['servername'] = [host, domain].join('.')
      else
        @server_facts['servername'] = host
      end
    end
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
