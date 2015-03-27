require 'spec_helper'

require 'puppet/server/http_client'
require 'java'
java_import com.puppetlabs.http.client.SimpleRequestOptions

describe 'Puppet::Server::HttpClient' do
  let :client do
    Puppet::Server::HttpClient.new("localhost", 0, {})
  end

  context "when settings are initialized with specific values" do
    before :all do
      @settings = Puppet::Server::HttpClient.settings
      Puppet::Server::HttpClient.initialize_settings(
        {"http_connect_timeout_milliseconds" => 42,
         "http_idle_timeout_milliseconds"  => 24})
    end

    after :all do
      Puppet::Server::HttpClient.initialize_settings(@settings)
    end

    subject do
      request_options = SimpleRequestOptions.new("http://i.love.ruby")
      client.send(:configure_timeouts, request_options)
      request_options
    end

    it 'then get_socket_timeout_milliseconds is 24' do
      expect(subject.get_socket_timeout_milliseconds).to eq(24)
    end

    it 'then get_connect_timeout_milliseconds is 42' do
      expect(subject.get_connect_timeout_milliseconds).to eq(42)
    end
  end

  context "when making a request that triggers a Java exception" do
    let :requests do
      {
        get: lambda { client.get('/', nil) },
        post: lambda { client.post('/', nil, nil) }
      }
    end

    [:get, :post].each do |request|
      describe "#{request} request" do
        subject { requests[request].call }

        it 'raises a SocketError' do
          expect { subject }.to raise_error SocketError
        end
        it 'raises a Puppet::Server::HttpClientError' do
          expect { subject }.to raise_error Puppet::Server::HttpClientError
        end
        it 'raises an Error with a specific message' do
          expect { subject }.to raise_error "Error executing http request"
        end
      end
    end
  end
end
