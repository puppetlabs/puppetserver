require 'puppet/server'

# Puppet::Pops::Lookup::KeyRecorder was added in Puppet 6.8 with
# this "null" behavior. If running with agent code < 6.8 define our
# own null recorder.
begin
  require 'puppet/pops/lookup/key_recorder'
rescue LoadError
  module Puppet
    module Pops
      module Lookup
        class KeyRecorder

          def self.singleton
            @recorder ||= new
          end

          def record(key)
          end
        end
      end
    end
  end
end

class Puppet::Server::KeyRecorder < Puppet::Pops::Lookup::KeyRecorder
  attr_accessor :lookups

  def initialize
    @lookups = Hash.new(0)
  end

  def record(key)
    @lookups[key] += 1
  end
end
