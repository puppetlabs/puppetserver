(ns puppetlabs.services.protocols.jruby-event-handler)

(defprotocol JRubyEventHandlerService
  "Describes a service which handles events related to the JRubyPuppet pool."

  (instance-requested
    [this action]
    "Called when a consumer asks to borrow a JRuby instance.  `action` is an
    identifier (usually a map) describing the reason for borrowing the
    JRuby instance.  It may be used for metrics and logging purposes.")

  (instance-borrowed
    [this action jruby-instance]
    "Called when a JRuby instance is successfully borrowed from the pool.  `action`
    is an identifier (usually a map) describing the reason for borrowing the
    JRuby instance.  `jruby-instance` is a reference to the borrowed instance.")

  (instance-returned
    [this jruby-instance]
    "Called when a jruby-instance is returned to the pool."))