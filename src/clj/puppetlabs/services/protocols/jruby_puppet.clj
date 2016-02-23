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
    "Mark the specified environment expired, in all JRuby instances.  Resets
    the cached class info for the environment's 'tag' to nil and 'last-updated'
    value to the number of milliseconds between now and midnight, January 1,
    1970 UTC.")

  (mark-all-environments-expired!
    [this]
    "Mark all cached environments expired, in all JRuby instances.  Resets the
    cached class info for all previously stored environment 'tags' to nil and
    'last-updated' value to the number of milliseconds between now and midnight,
    January 1, 1970 UTC.")

  (get-environment-class-info
    [this jruby-instance env-name]
    "Get class information for a specific environment")

  (get-environment-class-info-tag
    [this env-name]
    "Get a tag for the latest class information parsed for a specific
    environment")

  (get-environment-class-info-tag-last-updated
    [this env-name]
    "Get the 'time' that a tag was last set for a specific environment's
    class info.  Return value will be 'nil' if the tag has not previously
    been set for the environment or a schema/Int representing the
    number of milliseconds between the last time the tag was updated for an
    environment and midnight, January 1, 1970 UTC.")

  (set-environment-class-info-tag!
    [this env-name tag last-updated-before-tag-computed]
    "Set the tag computed for the latest class information parsed for a
    specific environment.  last-updated-before-tag-computed should represent
    what the client received for a 'get-environment-class-info-tag-last-updated'
    call for the environment made before it started doing the work to parse
    environment class info / compute the new tag.  If
    last-updated-before-tag-computed equals the 'last-updated' value stored in
    the cache for the environment, the new 'tag' will be stored for the
    environment and the corresponding 'last-updated' value will be updated to
    the number of milliseconds between now and midnight, January 1, 1970 UTC.
    If last-updated-before-tag-computed is different than the 'last-updated'
    value stored in the cache for the environment, the cache will remain
    unchanged as a result of this call.")

  (flush-jruby-pool!
    [this]
    "Flush all the current JRuby instances and repopulate the pool.")

  (register-event-handler
    [this callback]
    "Register a callback function to receive notifications when JRuby service events occur.
    The callback fn should accept a single arg, which will conform to the JRubyEvent schema."))
