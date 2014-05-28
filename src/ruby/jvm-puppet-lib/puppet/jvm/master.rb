require 'puppet'
require 'puppet/file_serving/metadata'
require 'puppet/file_serving/content'
require 'puppet/network/http/handler'
require 'puppet/network/http_pool'
require 'puppet/application/master'

require 'puppet/jvm'
require 'puppet/jvm/config'
require 'puppet/jvm/logger'
require 'puppet/jvm/http_client'

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
class Puppet::Jvm::Master
  include Java::com.puppetlabs.master.JRubyPuppet
  include Puppet::Network::HTTP::Handler

  def initialize(config)
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

    Puppet::Jvm::Logger.init_logging
    initialize_execution_stub

    Puppet.info("Puppet settings initialized; run mode: #{Puppet.run_mode.name}")

    master_run_mode = Puppet::Util::RunMode[:master]
    app_defaults = Puppet::Settings.app_defaults_for_run_mode(master_run_mode).
        merge({:name => "master",
               :node_cache_terminus => :write_only_yaml,
               :facts_terminus => 'yaml'})
    Puppet.settings.initialize_app_defaults(app_defaults)

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

    params = Hash[params.collect do |key, value|
      # Values for query string and/or form parameters which are specified
      # with array-like syntax will be parsed by Ring into a Clojure
      # PersistentVector, which derives from a Java List.  Need to
      # translate the Java List into a Ruby Array so that the request
      # handling logic in Ruby can make use of it.

      # For example, a query string of 'arr[]=one&arr[]=two" will be translated
      # at the Clojure Ring layer into an element with a key of "arr" and
      # value of '["one", "two"]' as a Clojure PersistentVector.  This
      # PersistentVector needs to be converted into a Ruby Array before
      # proceeding with the request processing.
      [key, value.java_kind_of?(Java::JavaUtil::List) ? value.to_a : value]
    end]

    params = decode_params(params)
    params.merge(client_information(request))
  end

  def client_cert(request)
    nil
  end

  def getSetting(setting)
    Puppet[setting]
  end

  def run_mode()
    Puppet.run_mode.name.to_s
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
  def pson_result(result)
    return nil if result.nil?
    result.to_pson
  end
end
