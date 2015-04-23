require 'puppet/server/network/http/handler'

class TestHandler
  include Puppet::Server::Network::HTTP::Handler
end

describe Puppet::Server::Network::HTTP::Handler do
  context "body" do
    it "should return a string body untouched" do
      body_string = "12345"
      handler = TestHandler.new()
      result = handler.body({"body" => body_string})
      expect(result).to be_a String
      expect(result).to eq body_string
    end

    it "should return an InputStream body back as a string" do
      bytes = Java::byte[3].new
      bytes[0] = -128
      bytes[1] = -127
      bytes[2] = -126
      bytes_as_stream = Java::Java::io::ByteArrayInputStream.new(bytes)
      handler = TestHandler.new()
      result = handler.body({"body" => bytes_as_stream})
      expect(result).to be_a String
      result_as_bytes = result.bytes.to_a
      expect(result_as_bytes[0]).to eq 128
      expect(result_as_bytes[1]).to eq 129
      expect(result_as_bytes[2]).to eq 130
    end

    it "should return a nil body back as nil" do
      handler = TestHandler.new()
      result = handler.body({"body" => nil})
      expect(result).to be_nil
    end

    it "should convert param values from a Java list to a Ruby array" do
      somestuff = java.util.ArrayList.new
      somestuff.add "one fish"
      somestuff.add "two fish"
      request = {"params" => {"something" => somestuff}}
      parsed_params = TestHandler.new().params(request)
      something = parsed_params[:something]
      expect(something.class).to eq Array
      expect(something[0]).to eq "one fish"
      expect(something[1]).to eq "two fish"
    end

  end
end