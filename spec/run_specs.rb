require "bundler/setup"
require "rspec"
require "spec_helper"

RSpec::Core::Runner.run(%w[./spec], $stderr, $stdout)
