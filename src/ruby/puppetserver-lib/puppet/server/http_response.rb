require 'puppet/http/response'

class Puppet::Server::HttpResponse < Puppet::HTTP::Response
  attr_reader :url

  # @param [com.puppetlabs.http.client.Response] java_response the response
  #        object from the Clojure HTTP client
  # @param [URI] url the URL requested for this response
  def initialize(java_response, url)
    @java_response = java_response
    @url = url
  end

  def code
    @java_response.get_status
  end

  def reason
    @java_response.get_reason_phrase
  end

  def body
    @java_response.get_body
  end

  def read_body
    raise ArgumentError, "A block is required" unless block_given?

    yield body
  end

  # The HTTP standard counts all codes 200-299 as successes
  def success?
    code > 199 && code < 300
  end

  def [](name)
    @java_response.get_headers[name]
  end

  # Yield each header name and value. Returns an enumerator if no block is given.
  def each_header
    @java_response.get_headers.each(&block)
  end

  # Drain the response body
  def drain
    body
    true
  end
end
