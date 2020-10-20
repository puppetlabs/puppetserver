require 'puppet/server/http_client'

describe "rspec via jruby" do
  it "can access our ruby source" do
    some_ruby_class = Puppet::Server::HttpClient.new
    expect(some_ruby_class).not_to be_nil
  end

  it "is actually running in JRuby" do
    expect(RUBY_PLATFORM).to eq('java')
  end
end
