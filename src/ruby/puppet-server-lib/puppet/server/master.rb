require 'puppet'
require 'puppet/file_serving/metadata'
require 'puppet/file_serving/content'
require 'puppet/network/http/handler'
require 'puppet/network/http_pool'
require 'puppet/application/master'

require 'puppet/util/profiler'

require 'puppet/server'
require 'puppet/server/config'
require 'puppet/server/puppet_config'
require 'puppet/server/logger'
require 'puppet/server/certificate'

require 'java'
java_import com.puppetlabs.puppetserver.ExecutionStubImpl

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
  include Puppet::Network::HTTP::Handler

  def initialize(puppet_config, puppet_server_config)
    Puppet::Server::Config.initialize_puppet_server(puppet_server_config)
    Puppet::Server::PuppetConfig.initialize_puppet(puppet_config)
    # Tell Puppet's network layer which routes we are willing handle - which is
    # all of them.  This is copied directly out of the WEBrick handler.
    register([Puppet::Network::HTTP::API::V2.routes,
              Puppet::Network::HTTP::API::V1.routes])
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

    # If a CN was provided then use that instead of IP info
    result[:authenticated] = false
    if cn = request["client-cert-cn"]
      result[:node] = cn
      result[:authenticated] = request["authenticated"]
    else
      result[:node] = resolve_node(result)
    end

    result
  end

end
