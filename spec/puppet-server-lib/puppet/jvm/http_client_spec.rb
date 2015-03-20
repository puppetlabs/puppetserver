require 'spec_helper'

require 'puppet/server/http_client'
require 'java'
java_import com.puppetlabs.http.client.SimpleRequestOptions

describe Puppet::Server::HttpClient do
  context "options" do
    let(:connect_timeout) { 42 }
    let(:socket_timeout) { 24 }

    it "timeout settings are properly set" do
      Puppet::Server::HttpClient.initialize_settings(
          {"http_connect_timeout" => connect_timeout,
           "http_socket_timeout"  => socket_timeout})

      client = Puppet::Server::HttpClient.new(nil, 0, {})
      request_options = SimpleRequestOptions.new("http://i.love.ruby")
      client.send(:configure_timeouts, request_options)
      request_options.get_socket_timeout_milliseconds.should == socket_timeout
      request_options.get_connect_timeout_milliseconds.should == connect_timeout
    end
  end
end
