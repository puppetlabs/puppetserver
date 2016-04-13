require 'puppet/server/network/http'

require 'puppet/network/http/handler'
require 'puppet/server/certificate'

java_import java.io.InputStream

module Puppet::Server::Network::HTTP::Handler
  include Puppet::Network::HTTP::Handler

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
    body = request["body"]
    if body.java_kind_of?(InputStream)
      body.to_io.read()
    else
      body
    end
  end

  def params(request)
    params = request["params"] || {}
    params = params_to_ruby(params)
    params = decode_params(params)
    params.merge(client_information(request))
  end

  def client_cert(request)
    if request['client-cert']
      Puppet::Server::Certificate.new(request['client-cert'])
    end
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

  def params_to_ruby(params)
    Hash[params.collect do |key, value|
      # Values for query string and/or form parameters which are specified
      # with array-like syntax will be parsed by Ring into a Clojure
      # PersistentVector, which derives from a Java List.  Need to
      # translate the Java List into a Ruby Array so that the request
      # handling logic in Ruby can make use of it.

      # For example, a query string of 'arr=one&arr=two" will be translated
      # at the Clojure Ring layer into an element with a key of "arr" and
      # value of '["one", "two"]' as a Clojure PersistentVector.  This
      # PersistentVector needs to be converted into a Ruby Array before
      # proceeding with the request processing.
      [key, value.java_kind_of?(Java::JavaUtil::List) ? value.to_a : value]
    end]
  end

end