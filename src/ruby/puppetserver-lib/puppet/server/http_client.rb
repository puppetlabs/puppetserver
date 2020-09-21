require 'puppet'
require 'puppet/server'
require 'puppet/server/config'
require 'puppet/server/http_response'
require 'puppet/http/client'
require 'base64'

require 'java'
java_import com.puppetlabs.http.client.RequestOptions
java_import com.puppetlabs.http.client.ClientOptions
java_import com.puppetlabs.http.client.CompressType
java_import com.puppetlabs.http.client.ResponseBodyType
SyncHttpClient = com.puppetlabs.http.client.Sync

class Puppet::Server::HttpClientError < SocketError
  attr_reader :cause

  def initialize(message, cause = nil)
    super(message)
    @cause = cause
  end
end

class Puppet::Server::HttpClient < Puppet::HTTP::Client

  OPTION_DEFAULTS = {
      :verify => nil,
      :redirect_limit => 10,
  }

  # Store a java HashMap of settings related to the http client
  def self.initialize_settings(settings)
    @settings = settings.select { |k,v|
      ["server_id",
       "metric_registry",
       "ssl_protocols",
       "cipher_suites",
       "http_connect_timeout_milliseconds",
       "http_idle_timeout_milliseconds"].include? k
    }
  end

  def self.settings
    @settings ||= {}
  end

  def initialize(options = {})
    options = OPTION_DEFAULTS.merge(options)
  end

  def get(url, headers: {}, params: {}, options: {}, &block)
    request_options = create_common_request_options(url, headers, params, options)
    response = self.class.client_get(request_options)
    Puppet::Server::HttpResponse.new(response, url)
  end

  def post(url, body, headers: {}, params: {}, options: {}, &block)
    request_options = create_common_request_options(url, headers, params, options)
    request_options.set_body(body)

    compress = options[:compress]
    if compress
      compress_as_sym = compress.to_sym
      if compress_as_sym == :gzip
        request_options.set_compress_request_body(CompressType::GZIP)
      else
        raise ArgumentError, "Unsupported compression specified for request: #{compress}"
      end
    end

    response = self.class.client_post(request_options)
    Puppet::Server::HttpResponse.new(response, url)
  end

  def create_common_request_options(url, headers, params, options)
    # If credentials were supplied for HTTP basic auth, add them into the headers.
    # This is based on the code in lib/puppet/reports/http.rb.
    credentials = options[:basic_auth]
    if credentials
      # http://en.wikipedia.org/wiki/Basic_access_authentication#Client_side
      encoded = Base64.strict_encode64("#{credentials[:user]}:#{credentials[:password]}")
      authorization = "Basic #{encoded}"

      if headers["Authorization"] && headers["Authorization"] != authorization
        raise "Existing 'Authorization' header conflicts with supplied HTTP basic auth credentials."
      end

      headers["Authorization"] = authorization
    end

    # Ensure multiple requests are not made on the same connection
    headers["Connection"] = "close"

    if url.is_a?(String)
      url = URI(url)
    end
    url = encode_query(url, params)

    # Java will reparse the string into its own URI object
    request_options = RequestOptions.new(url.to_s)
    if options[:metric_id]
      request_options.set_metric_id(options[:metric_id])
    end
    request_options.set_headers(headers)
    request_options.set_as(ResponseBodyType::TEXT)
  end

  def self.terminate
    unless @client.nil?
      @client.close
      @client = nil
    end
  end

  private

  def self.configure_timeouts(client_options)
    settings = self.settings

    if settings.has_key?("http_connect_timeout_milliseconds")
      client_options.set_connect_timeout_milliseconds(settings["http_connect_timeout_milliseconds"])
    end

    if settings.has_key?("http_idle_timeout_milliseconds")
      client_options.set_socket_timeout_milliseconds(settings["http_idle_timeout_milliseconds"])
    end
  end

  def self.configure_ssl(client_options)
    client_options.set_ssl_context(Puppet::Server::Config.ssl_context)

    settings = self.settings

    if settings.has_key?("ssl_protocols")
      client_options.set_ssl_protocols(settings["ssl_protocols"])
    end
    if settings.has_key?("cipher_suites")
      client_options.set_ssl_cipher_suites(settings["cipher_suites"])
    end
  end

  def self.configure_metrics(client_options)
    settings = self.settings
    if settings.has_key?("metric_registry")
      client_options.set_metric_registry(settings["metric_registry"])
    end
    if settings.has_key?("server_id")
      client_options.set_server_id(settings["server_id"])
    end
  end

  def self.create_client_options
    client_options = ClientOptions.new
    self.configure_timeouts(client_options)
    self.configure_ssl(client_options)
    self.configure_metrics(client_options)
    client_options.set_enable_url_metrics(false)
    client_options
  end

  def self.create_client
    client_options = create_client_options
    SyncHttpClient.createClient(client_options)
  end

  def self.client
    @client ||= create_client
  end

  def self.client_post(request_options)
    self.client.post(request_options)
  rescue Java::ComPuppetlabsHttpClient::HttpClientException => e
    raise Puppet::Server::HttpClientError.new(e.message, e)
  end

  def self.client_get(request_options)
    self.client.get(request_options)
  rescue Java::ComPuppetlabsHttpClient::HttpClientException => e
    raise Puppet::Server::HttpClientError.new(e.message, e)
  end


  def create_session
    raise NotImplementedError
  end

  def connect(uri, options: {}, &block)
    raise NotImplementedError
  end

  def head(url, headers: {}, params: {}, options: {})
    raise NotImplementedError
  end

  def put(url, headers: {}, params: {}, options: {})
    raise NotImplementedError
  end

  def delete(url, headers: {}, params: {}, options: {})
    raise NotImplementedError
  end
end
