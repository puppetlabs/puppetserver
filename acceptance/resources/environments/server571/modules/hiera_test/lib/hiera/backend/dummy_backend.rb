Puppet.debug "Loaded dummy_backend! 8B06E7B6-8930-4CAB-B229-9A40A3F41AB8"
class Hiera
  module Backend
   class Dummy_backend
     def lookup(key, scope, order_override, resolution_type)
       Puppet.debug "Lookup called! key=#{key} 8B06E7B6-8930-4CAB-B229-9A40A3F41AB8"
       return "4EB0033A-FC4E-4D7D-9453-5A9938B6FF61"
     end
   end
  end
end
