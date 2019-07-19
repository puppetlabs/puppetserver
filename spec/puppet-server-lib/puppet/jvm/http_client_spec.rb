require 'spec_helper'

require 'puppet/server/http_client'
require 'java'

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
      Puppet::Server::HttpClient.create_client_options
    end

    it 'then get_socket_timeout_milliseconds is 24' do
      expect(subject.get_socket_timeout_milliseconds).to eq(24)
    end

    it 'then get_connect_timeout_milliseconds is 42' do
      expect(subject.get_connect_timeout_milliseconds).to eq(42)
    end
  end

  context 'when making a request with basic auth' do
    let(:url) { '/' }
    let(:headers) { {} }
    let(:options) { {} }

    describe '#create_common_request_options' do
      subject { client.create_common_request_options(url, headers, options) }

      context 'with auth provided via options' do
        let(:options) { {basic_auth: {user: 'username', password: 'secret'}} }

        it 'has the Authorization header set' do
          expect(subject.headers['Authorization']).to eq('Basic dXNlcm5hbWU6c2VjcmV0')
        end
      end

      context 'with auth provided via headers' do
        let(:headers) { {'Authorization' => 'Basic dXNlcm5hbWU6c2VjcmV0'} }

        it 'has the Authorization header set' do
          expect(subject.headers['Authorization']).to eq('Basic dXNlcm5hbWU6c2VjcmV0')
        end

        context 'with match auth via options' do
          let(:options) { {basic_auth: {user: 'username', password: 'secret'}} }

          it 'has the Authorization header set' do
            expect(subject.headers['Authorization']).to eq('Basic dXNlcm5hbWU6c2VjcmV0')
          end
        end

        context 'with non-match auth via options' do
          let(:options) { {basic_auth: {user: 'username', password: 'mismatch'}} }

          it 'raises an exception' do
            expect { subject }.to raise_error(StandardError, /Existing 'Authorization' header conflicts/)
          end
        end
      end
    end
  end

  context "when making a request that triggers a Java exception" do
    let :requests do
      headers = {}
      body = nil
      {
        get: lambda { client.get('/', headers) },
        post: lambda { client.post('/', body, headers) }
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
