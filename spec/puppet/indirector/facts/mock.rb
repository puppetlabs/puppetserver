require 'puppet/node/facts'
require 'puppet/indirector/this_is_stupid'

module Puppet
  class Node
    class Facts
      class Mock < Puppet::Indirector::ThisIsStupid

        def self.indirection_name
          :facts
        end

        def self.name
          :mock
        end

        def save(request)
          true
        end

        def find(request)
          true
        end
      end
    end
  end
end
