require 'puppet'
require 'puppet/test/test_helper'

RSpec.configure do |config|
  config.mock_with :rspec

  Puppet::Test::TestHelper.initialize

  config.before(:all) do
    Puppet::Test::TestHelper.before_all_tests
  end

  config.after(:all) do
    Puppet::Test::TestHelper.after_all_tests
  end

  config.before(:each) do
    Puppet::Test::TestHelper.before_each_test
  end

  config.after(:each) do
    Puppet::Test::TestHelper.after_each_test
  end
end
