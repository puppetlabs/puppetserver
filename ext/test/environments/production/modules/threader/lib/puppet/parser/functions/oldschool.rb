module Puppet::Parser::Functions
  newfunction(:oldschool, :type => :rvalue) do |args|
    return 'the old way of functions'
  end
end
