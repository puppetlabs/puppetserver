(ns puppetlabs.services.protocols.jruby-puppet)

(defprotocol JRubyPuppetService
  "Describes the JRubyPuppet provider service which pools JRubyPuppet instances."

  (borrow-instance
    [this reason]
    "Borrows an instance from the JRubyPuppet interpreter pool. If there are no
    interpreters left in the pool then the operation blocks until there is one
    available. A timeout (integer measured in milliseconds) can be configured
    which will either return an interpreter if one is available within the
    timeout length, or will return nil after the timeout expires if no
    interpreters are available. This timeout defaults to 1200000 milliseconds.

    `reason` is an identifier (usually a map) describing the reason for borrowing the
    JRuby instance.  It may be used for metrics and logging purposes.")

  (return-instance
    [this jrubypuppet-instance reason]
    "Returns the JRubyPuppet interpreter back to the pool.

    `reason` is an identifier (usually a map) describing the reason for borrowing the
    JRuby instance.  It may be used for metrics and logging purposes, so for
    best results it should be set to the same value as it was set during the
    `borrow-instance` call.")

  (free-instance-count
    [this]
    "The number of free JRubyPuppet instances left in the pool.")

  (mark-environment-expired!
    [this env-name]
    "Mark the specified environment expired, in all JRuby instances.")

  (mark-all-environments-expired!
    [this]
    "Mark all cached environments expired, in all JRuby instances.")

  (flush-jruby-pool!
    [this]
    "Flush all the current JRuby instances and repopulate the pool.")

  (register-event-handler
    [this callback]
    "Register a callback function to receive notifications when JRuby service events occur.
    The callback fn should accept a single arg, which will conform to the JRubyEvent schema.")

  (with-lock
    [this f]
    "Acquires a lock on the pool, executes f, and releases the lock."))
