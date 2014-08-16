require 'puppet/server/http_client'

describe "rspec via jruby" do
  it "can access our ruby source" do
    some_ruby_class = Puppet::Server::HttpClient.new('foo', 1234)
    some_ruby_class.should_not be_nil
  end

  it "is actually running in JRuby" do
    RUBY_PLATFORM.should == 'java'
  end
end
