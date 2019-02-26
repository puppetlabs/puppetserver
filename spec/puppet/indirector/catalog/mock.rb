require 'puppet/indirector/this_is_stupid'
require 'puppet/resource/catalog'

module Puppet
  class Resource
    class Catalog
      class Mock < Puppet::Indirector::ThisIsStupid
        def save(request)
        end
      end
    end
  end
end
