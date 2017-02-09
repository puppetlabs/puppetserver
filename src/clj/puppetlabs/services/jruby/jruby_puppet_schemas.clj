(ns puppetlabs.services.jruby.jruby-puppet-schemas
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def JRubyPuppetConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :master-conf-dir - file path to puppetmaster's conf dir;
        if not specified, will use the puppet default.

    * :master-code-dir - file path to puppetmaster's code dir;
        if not specified, will use the puppet default.

    * :master-var-dir - path to the puppetmaster's var dir;
        if not specified, will use the puppet default.

    * :master-run-dir - path to the puppetmaster's run dir;
        if not specified, will use the puppet default.

    * :master-log-dir - path to the puppetmaster's log dir;
        if not specified, will use the puppet default.

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

    * :use-legacy-auth-conf - Whether to use the legacy core Puppet auth.conf
        (true) or trapperkeeper-authorization (false) to authorize requests
        being made to core Puppet endpoints."
  {:master-conf-dir (schema/maybe schema/Str)
   :master-code-dir (schema/maybe schema/Str)
   :master-var-dir (schema/maybe schema/Str)
   :master-run-dir (schema/maybe schema/Str)
   :master-log-dir (schema/maybe schema/Str)
   :http-client-ssl-protocols [schema/Str]
   :http-client-cipher-suites [schema/Str]
   :http-client-connect-timeout-milliseconds schema/Int
   :http-client-idle-timeout-milliseconds schema/Int
   :http-client-metrics-enabled schema/Bool
   :max-requests-per-instance schema/Int
   :use-legacy-auth-conf schema/Bool})

