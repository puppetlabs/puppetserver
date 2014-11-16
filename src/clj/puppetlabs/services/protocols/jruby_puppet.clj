(ns puppetlabs.services.protocols.jruby-puppet)

(defprotocol JRubyPuppetService
  "Describes the JRubyPuppet provider service which pools JRubyPuppet instances."

  (borrow-instance
    [this]
    [this timeout]
    "Borrows an instance from the JRubyPuppet interpreter pool. If there are no
    interpreters left in the pool then the operation blocks until there is one
    available. A timeout (integer measured in milliseconds) can be provided
    which will either return an interpreter if one is available within the
    timeout length, or will return nil after the timeout expires if no
    interpreters are available.")

  (return-instance
    [this jrubypuppet-instance]
    "Returns the JRubyPuppet interpreter back to the pool.")

  (free-instance-count
    [this]
    "The number of free JRubyPuppet instances left in the pool.")

  (mark-all-environments-expired!
    [this]
    "Mark all cached environments expired, in all JRuby instances.")

  (flush-jruby-pool!
    [this]
    "Flush all the current JRuby instances and repopulate the pool."))

      