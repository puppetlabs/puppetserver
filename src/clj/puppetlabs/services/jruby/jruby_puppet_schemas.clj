(ns puppetlabs.services.jruby.jruby-puppet-schemas
  (:require [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def JRubyPuppetConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :server-conf-dir - file path to Puppet Server's conf dir;
        if not specified, will use the puppet default.

    * :server-code-dir - file path to Puppet Server's code dir;
        if not specified, will use the puppet default.

    * :server-var-dir - path to the Puppet Server's var dir;
        if not specified, will use the puppet default.

    * :server-run-dir - path to the Puppet Server's run dir;
        if not specified, will use the puppet default.

    * :server-log-dir - path to the Puppet Server's log dir;
        if not specified, will use the puppet default.

    * :track-lookups - a boolean to turn on tracking hiera lookups during compilation;
        if not specified, no tracking is enabled.

    * :disable-i18n - a boolean to pass to Puppet to control whether or not to translate;
        if not specified or false, the flag is not passed to Puppet's initialization.

    * :http-client-ssl-protocols - A list of legal SSL protocols that may be used
        when https client requests are made.

    * :http-client-cipher-suites - A list of legal SSL cipher suites that may
        be used when https client requests are made.

    * :http-client-connect-timeout-milliseconds - The amount of time, in
        milliseconds, that an outbound HTTP connection will wait to connect
        before giving up.  If 0, the timeout is infinite and if negative, the
        value is undefined in the application and governed by the system default
        behavior.

    * :http-client-idle-timeout-milliseconds - The amount of time, in
        milliseconds, that an outbound HTTP connection will wait for data to be
        available after a request is sent before closing the socket.  If 0, the
        timeout is infinite and if negative, the value is undefined by the
        application and is governed by the default system behavior.

    * :http-client-metrics-enabled - Whether to use http client metrics.

    * :boltlib-path - Optional array containing path(s) to bolt modules. This path
         will be prepended to AST compilation modulepath. This is required for
         compiling AST that contains bolt types."
  {(schema/optional-key :server-conf-dir) (schema/maybe schema/Str)
   (schema/optional-key :server-code-dir) (schema/maybe schema/Str)
   (schema/optional-key :server-var-dir) (schema/maybe schema/Str)
   (schema/optional-key :server-run-dir) (schema/maybe schema/Str)
   (schema/optional-key :server-log-dir) (schema/maybe schema/Str)
   (schema/optional-key :track-lookups) schema/Bool
   (schema/optional-key :disable-i18n) schema/Bool
   :http-client-ssl-protocols [schema/Str]
   :http-client-cipher-suites [schema/Str]
   :http-client-connect-timeout-milliseconds schema/Int
   :http-client-idle-timeout-milliseconds schema/Int
   :http-client-metrics-enabled schema/Bool
   :max-requests-per-instance schema/Int
   (schema/optional-key :boltlib-path) [schema/Str]
   ;; Deprecated in favor of their `server-*` counterparts.
   ;; to be removed in Puppet 8.
   (schema/optional-key :master-conf-dir) (schema/maybe schema/Str)
   (schema/optional-key :master-code-dir) (schema/maybe schema/Str)
   (schema/optional-key :master-var-dir) (schema/maybe schema/Str)
   (schema/optional-key :master-run-dir) (schema/maybe schema/Str)
   (schema/optional-key :master-log-dir) (schema/maybe schema/Str)})

