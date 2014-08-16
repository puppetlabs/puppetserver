require 'puppet'
require 'puppet/file_serving/metadata'
require 'puppet/file_serving/content'
require 'puppet/network/http/handler'
require 'puppet/network/http_pool'
require 'puppet/application/master'

require 'puppet/util/profiler'

require 'puppet/server'
require 'puppet/server/config'
require 'puppet/server/logger'
require 'puppet/server/http_client'
require 'puppet/server/jvm_profiler'
require 'puppet/server/certificate'

require 'java'

java_import com.puppetlabs.master.ExecutionStubImpl

##
## This class is a bridge between the puppet ruby code and the java interface
## `com.puppetlabs.master.JRubyPuppet`.  The first `include` line in the class
## is some JRuby magic that causes this class to "implement" the Java interface.
## So, in this class we can make calls into the puppet ruby code, but from
## outside (in the clojure/Java code), we can interact with an instance of this
## class simply as if it were an instance of the Java interface; thus, consuming
## code need not be aware of any of the JRuby implementation details.
##
class Puppet::Server::Master
  include Java::com.puppetlabs.master.JRubyPuppet
  include Puppet::Network::HTTP::Handler

  def initialize(config, profiler)
    # Puppet.initialize_settings is the method that you call if you want to use
    # the puppet code as a library.  (It is called implicitly by all of the puppet
    # cli tools.)  Here we can basically pass through any settings that we wish
    # to modify/override in the same syntax as you would pass in cli args to
    # set them.
    #
    # `config` is a map whose keys are the names of the settings that we wish
    # to override, and whose values are the desired values for the settings.
    Puppet.initialize_settings(
        config.reduce([]) do |acc, entry|
          acc << "--#{entry[0]}" << entry[1]
        end
    )
    Puppet[:trace] = true
    # TODO: find out if this is actually the best way to set the run mode
    Puppet.settings.preferred_run_mode = :master

    Puppet::Server::Logger.init_logging
    initialize_execution_stub

    if profiler
      Puppet::Util::Profiler.add_profiler(Puppet::Server::JvmProfiler.new(profiler))
    end

    Puppet.info("Puppet settings initialized; run mode: #{Puppet.run_mode.name}")

    master_run_mode = Puppet::Util::RunMode[:master]
    app_defaults = Puppet::Settings.app_defaults_for_run_mode(master_run_mode).
        merge({:name => "master",
               :node_cache_terminus => :write_only_yaml,
               :facts_terminus => 'yaml'})
    Puppet.settings.initialize_app_defaults(app_defaults)

    reset_environment_context()

    Puppet.settings.use :main, :master, :ssl, :metrics

    Puppet::FileServing::Content.indirection.terminus_class = :file_server
    Puppet::FileServing::Metadata.indirection.terminus_class = :file_server
    Puppet::FileBucket::File.indirection.terminus_class = :file

    Puppet::Node.indirection.cache_class = Puppet[:node_cache_terminus]

    # Tell Puppet's network layer which routes we are willing handle - which is
    # all of them.  This is copied directly out of the WEBrick handler.
    register([Puppet::Network::HTTP::API::V2.routes,
              Puppet::Network::HTTP::API::V1.routes])
  end

  def initialize_execution_stub
    Puppet::Util::ExecutionStub.set do |command,options|
      ExecutionStubImpl.executeCommand(command.join(" "))
    end
  end

  def handleRequest(request)
    response = {}

    process(request, response)
    # 'process' returns only the status -
    # `response` now contains all of the response data

    body = response[:body]
    body_to_return =
        if body.is_a? String
          body
        elsif body.is_a? IO
          body.to_inputstream
        else
          raise "Don't know how to handle response body from puppet, which is a #{body.class}"
        end

    com.puppetlabs.master.JRubyPuppetResponse.new(
        response[:status],
        body_to_return,
        response[:content_type],
        response["X-Puppet-Version"])
  end

  # Set the response up, with the body and status.
  def set_response(response, body, status = 200)
    response[:body] = body
    response[:status] = status
  end

  # Set the specified format as the content type of the response.
  def set_content_type(response, format)
    response[:content_type] = format_to_mime(format)
  end

  # Retrieve all headers from the http request, as a hash with the header names
  # (lower-cased) as the keys
  def headers(request)
    request["headers"]
  end

  def http_method(request)
    request["request-method"]
  end

  def path(request)
    request["uri"]
  end

  def body(request)
    request["body"]
  end

  def params(request)
    params = request["params"] || {}
    params = decode_params(params)
    params.merge(client_information(request))
  end

  def client_cert(request)
    if request['client-cert']
      Puppet::Server::Certificate.new(request['client-cert'])
    end
  end

  def getSetting(setting)
    Puppet[setting]
  end

  def run_mode()
    Puppet.run_mode.name.to_s
  end

  def puppetVersion()
    Puppet.version
  end

  # Retrieve node/cert/ip information from the request object.
  def client_information(request)
    result = {}
    if ip = request["remote-addr"]
      result[:ip] = ip
    end

    # If they have a certificate (which will almost always be true)
    # then we get the hostname from the cert, instead of via IP
    # info
    result[:authenticated] = false
    if cn = request["client-cert-cn"]
      result[:node] = cn
      result[:authenticated] = true
    else
      result[:node] = resolve_node(result)
    end

    result
  end

  private
  def reset_environment_context
    # The following lines were copied for the most part from the run() method
    # in the Puppet::Application class from .../lib/puppet/application.rb
    # in core Ruby Puppet code.  The logic in the Puppet::Application class is
    # executed by the core Ruby Puppet master during its initialization.
    #
    # The call to Puppet.base_context is needed in order for the modulepath
    # settings just implicitly reprocessed for master run mode to be
    # reset onto the Environment objects that later Ruby Puppet requests
    # will use (e.g., for agent pluginsyncs).
    #
    # It would be better for the logic below to be put in a location where
    # both the core Ruby Puppet and Puppet Server masters can use the same
    # implementation.  A separate ticket, PE-4356, was filed to cover this
    # follow-on work.

    Puppet.push_context(Puppet.base_context(Puppet.settings),
                        "Update for application settings (Puppet Server).")
    # This use of configured environment is correct, this is used to establish
    # the defaults for an application that does not override, or where an override
    # has not been made from the command line.
    #
    configured_environment_name = Puppet[:environment]
    configured_environment =
      Puppet.lookup(:environments).get(configured_environment_name)
    configured_environment =
      configured_environment.override_from_commandline(Puppet.settings)

    if configured_environment.nil?
      fail(Puppet::Environments::EnvironmentNotFound, configured_environment_name)
    end
    Puppet.push_context({:current_environment => configured_environment},
      "Update current environment from JVM puppet master's configuration")

    require 'puppet/util/instrumentation'
    Puppet::Util::Instrumentation.init
  end
end
