(ns puppetlabs.master.services.protocols.jruby-puppet)

(defprotocol JRubyPuppetService
  "Describes the JRubyPuppet provider service which pools JRubyPuppet instances."

  (borrow-instance
    [this pool-desc]
    [this pool-desc timeout]
    "Borrows an instance of a JRubyPuppet interpreter from a pool which
     matches the provided descriptor. The pool descriptor, pool-desc, is a
     map which contains a list of attribtues which uniquely identify a
     JRubyPuppet pool from the Puppet Server configuration. Currently only the
     :environment key is honored.

     If there are no interpreters left in the pool then the operation blocks
     until there is one available. A timeout (integer measured in
     milliseconds) can be provided which will either return an
     interpreter if one is available within the timeout length, or will
     return nil after the timeout expires if no interpreters are available.")

  (return-instance
    [this pool-desc jrubypuppet-instance]
    "Returns the JRubyPuppet interpreter back to the pool described by pool-desc.")

  (free-instance-count
    [this pool-desc]
    "The number of free JRubyPuppet instances left in the described pool.")

  (get-default-pool-descriptor
    [this]
    "Returns the default JRuby pool descriptor.  Normally, callers should
    create an appropriate pool descriptor before calling `borrow-instance`;
    however, in certain (rare) cases, there is no applicable pool descriptor,
    and this function should be called to get the default."))
