require 'puppet/node/facts'
require 'puppet/indirector/this_is_stupid'

module Puppet
  class Node
    class Facts
      class Mock < Puppet::Indirector::ThisIsStupid

        def save(request)
        end

        def find(request)
        end
      end
    end
  end
end
