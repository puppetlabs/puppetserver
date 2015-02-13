require 'puppet/server/puppet_config'

describe Puppet::Server::PuppetConfig do
  mock_puppet_config = {}
  context "Puppet's log level" do
    it "is set as high as possible during initialization, " +
       "so that all log messages make it to logback" do

      Puppet::Server::PuppetConfig.initialize_puppet mock_puppet_config

      # The first line here is probably sufficient, but there's been some
      # changes in Puppet recently around logging, so it seems worthwhile
      # to test both of these.
      Puppet[:log_level].should == "debug"
      Puppet::Util::Log.level.should == :debug
    end
  end
end
