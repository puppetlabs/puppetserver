require 'spec_helper'

require 'puppet/server/http_client'
require 'java'
java_import com.puppetlabs.http.client.SimpleRequestOptions

describe Puppet::Server::HttpClient do
  context "options" do
    let(:connect_timeout) { 42 }
    let(:idle_timeout) { 24 }

    it "timeout settings are properly set" do
      Puppet::Server::HttpClient.initialize_settings(
          {"http_connect_timeout_milliseconds" => connect_timeout,
           "http_idle_timeout_milliseconds"  => idle_timeout})

      client = Puppet::Server::HttpClient.new(nil, 0, {})
      request_options = SimpleRequestOptions.new("http://i.love.ruby")
      client.send(:configure_timeouts, request_options)
      expect(request_options.get_socket_timeout_milliseconds).to equal(idle_timeout)
      expect(request_options.get_connect_timeout_milliseconds).to equal(connect_timeout)
    end
  end

  context "requests" do
    it "throw Ruby SocketErrors instead of Java exceptions" do
      client = Puppet::Server::HttpClient.new("localhost", 0, {})
      [lambda { client.get('/', nil) },
       lambda { client.post('/', nil, nil) }
      ].each do |request_block|
        expect(&request_block).to raise_error { |error|
          expect(error).to be_a(SocketError)
          expect(error).to be_a(Puppet::Server::HttpClientError)
          expect(error.message).to eq('com.puppetlabs.http.client.HttpClientException: Error executing http request')
        }
      end
    end
  end
end
