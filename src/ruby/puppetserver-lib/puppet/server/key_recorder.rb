require 'puppet/server'
require 'puppet/pops/lookup/key_recorder'

class Puppet::Server::KeyRecorder < Puppet::Pops::Lookup::KeyRecorder
  def initialize
    @lookups = Hash.new(0)
  end

  def record(key)
    @lookups[key] += 1
  end
end
