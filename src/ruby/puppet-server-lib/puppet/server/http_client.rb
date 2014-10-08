require 'puppet'
require 'puppet/server'
require 'puppet/server/config'
require 'net/http'
require 'base64'

require 'java'
java_import com.puppetlabs.http.client.SyncHttpClient
java_import com.puppetlabs.http.client.RequestOptions
java_import com.puppetlabs.http.client.ResponseBodyType

class Puppet::Server::HttpClient

  OPTION_DEFAULTS = {
      :use_ssl => true,
      :verify => nil,
      :redirect_limit => 10,
  }

  def initialize(server, port, options = {})
    options = OPTION_DEFAULTS.merge(options)

    @server = server
    @port = port
    @use_ssl = options[:use_ssl]
    @protocol = @use_ssl ? "https" : "http"
  end

  def post(url, body, headers, options = {})

    # If credentials were supplied for HTTP basic auth, add them into the headers.
    # This is based on the code in lib/puppet/reports/http.rb.
    credentials = options[:basic_auth]
    if credentials
      if headers["Authorization"]
        raise "Existing 'Authorization' header conflicts with supplied HTTP basic auth credentials."
      end

      # http://en.wikipedia.org/wiki/Basic_access_authentication#Client_side
      headers["Authorization"] =
          "Basic: #{Base64.encode64 "#{credentials[:user]}:#{credentials[:password]}"}"
    end

    request_options = RequestOptions.new(build_url(url))
    request_options.set_headers(headers)
    request_options.set_as(ResponseBodyType::TEXT)
    request_options.set_body(body)
    configure_ssl(request_options)
    response = SyncHttpClient.post(request_options)
    ruby_response(response)
  end

  def get(url, headers)
    request_options = RequestOptions.new(build_url(url))
    request_options.set_headers(headers)
    request_options.set_as(ResponseBodyType::TEXT)
    configure_ssl(request_options)
    response = SyncHttpClient.get(request_options)
    ruby_response(response)
  end


  private

  def configure_ssl(request_options)
    return unless @use_ssl
    request_options.set_ssl_context(Puppet::Server::Config.ssl_context)
  end

  def remove_leading_slash(url)
    url.sub(/^\//, "")
  end

  def build_url(url)
    "#{@protocol}://#{@server}:#{@port}/#{remove_leading_slash(url)}"
  end

  # Copied from Net::HTTPResponse because it is private there.
  def ruby_response_class(code)
    Net::HTTPResponse::CODE_TO_OBJ[code] or
    Net::HTTPResponse::CODE_CLASS_TO_OBJ[code[0,1]] or
    Net::HTTPUnknownResponse
  end

  def ruby_response(response)
    clazz = ruby_response_class(response.status.to_s)
    result = clazz.new(nil, response.status.to_s, nil)
    result.body = response.body
    # TODO: this is nasty, nasty.  But apparently there is no way to create
    # an instance of Net::HttpResponse from outside of the library and have
    # the body be readable, unless you do stupid things like this.
    # We need to figure out how to add some spec tests to make sure that this
    # fragile thing doesn't break in a future version of jruby. (PE-3356)
    result.instance_variable_set(:@read, true)

    response.headers.each do |k,v|
      result[k] = v
    end
    result
  end

end