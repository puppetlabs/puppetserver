require "bundler/setup"
require "rspec"
require "spec_helper"

exit RSpec::Core::Runner.run(ARGV, $stderr, $stdout)
