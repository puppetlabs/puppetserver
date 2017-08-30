(ns puppetlabs.services.protocols.jruby-puppet)

(defprotocol JRubyPuppetService
  "Describes the JRubyPuppet provider service which pools JRubyPuppet instances."

  (free-instance-count
    [this]
    "The number of free JRubyPuppet instances left in the pool.")

  (mark-environment-expired!
    [this env-name]
    "Mark the specified environment expired, in all JRuby instances.  Resets
    the cached class info for the environment's 'tag' to nil and increments the
    'cache-generation-id' value.")

  (mark-all-environments-expired!
    [this]
    "Mark all cached environments expired, in all JRuby instances.  Resets the
    cached class info for all previously stored environment 'tags' to nil and
    increments the 'cache-generation-id' value.")

  (get-environment-class-info
    [this jruby-instance env-name]
    "Get class information for a specific environment")

  (get-environment-class-info-tag
    [this env-name]
    "Get a tag for the latest class information parsed for a specific
    environment")

  (get-environment-class-info-cache-generation-id!
    [this env-name]
    "Get the current cache generation id for a specific environment's class
    info.  If no entry for the environment had existed at the point this
    function was called this function would, as a side effect, populate a new
    entry for that environment into the cache.")

  (set-environment-class-info-tag!
    [this env-name tag cache-generation-id-before-tag-computed]
    "Set the tag computed for the latest class information parsed for a
    specific environment.  cache-generation-id-before-tag-computed should
    represent what the client received for a
    'get-environment-class-info-cache-generation-id!' call for the environment
    made before it started doing the work to parse environment class info /
    compute the new tag.  If cache-generation-id-before-tag-computed equals
    the 'cache-generation-id' value stored in the cache for the environment, the
    new 'tag' will be stored for the environment and the corresponding
    'cache-generation-id' value will be incremented.  If
    cache-generation-id-before-tag-computed is different than the
    'cache-generation-id' value stored in the cache for the environment, the
    cache will remain unchanged as a result of this call.")

  (get-environment-module-info
    [this jruby-instance env-name]
    "Get module information for a specific environment")

  (get-all-environment-module-info
    [this jruby-instance]
    "Get all module information for all environments")

  (get-task-data
    [this jruby-instance env-name module-name task-name]
    "Get information (:metadata_file and :files) for a specific task. Returns a JRuby hash.")

  (get-tasks
    [this jruby-instance env-name]
    "Get list of task names and environment information.")

  (flush-jruby-pool!
    [this]
    "Flush all the current JRuby instances and repopulate the pool.")

  (get-pool-context
    [this]
    "Get the pool context out of the service context.")

  (register-event-handler
    [this callback]
    "Register a callback function to receive notifications when JRuby service events occur.
    The callback fn should accept a single arg, which will conform to the JRubyEvent schema."))
