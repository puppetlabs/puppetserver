(ns puppetlabs.services.jruby.jruby-puppet-schemas
  (:require [schema.core :as schema])
  (:import (org.jruby Main Main$Status RubyInstanceConfig)
           (com.puppetlabs.puppetserver.jruby ScriptingContainer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def supported-jruby-compile-modes
  #{:jit :force :off})

(def SupportedJRubyCompileModes
  "Schema defining the supported values for the JRuby CompileMode setting."
  (apply schema/enum supported-jruby-compile-modes))

(def CombinedJRubyPuppetConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :ruby-load-path - a vector of file paths, containing the locations of puppet source code.

    * :gem-home - The location that JRuby gems are stored

    * :compile-mode - The value to use for JRuby's CompileMode setting.  Legal
        values are `:jit`, `:force`, and `:off`.  Defaults to `:off`.

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

    * :max-active-instances - The maximum number of JRubyPuppet instances that
        will be pooled.

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

    * :use-legacy-auth-conf - Whether to use the legacy core Puppet auth.conf
        (true) or trapperkeeper-authorization (false) to authorize requests
        being made to core Puppet endpoints."
  {:ruby-load-path [schema/Str]
   :gem-home schema/Str
   :compile-mode SupportedJRubyCompileModes
   :master-conf-dir (schema/maybe schema/Str)
   :master-code-dir (schema/maybe schema/Str)
   :master-var-dir (schema/maybe schema/Str)
   :master-run-dir (schema/maybe schema/Str)
   :master-log-dir (schema/maybe schema/Str)
   :http-client-ssl-protocols [schema/Str]
   :http-client-cipher-suites [schema/Str]
   :http-client-connect-timeout-milliseconds schema/Int
   :http-client-idle-timeout-milliseconds schema/Int
   :borrow-timeout schema/Int
   :max-active-instances schema/Int
   :max-requests-per-instance schema/Int
   :use-legacy-auth-conf schema/Bool})

(def JRubyPuppetConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :ruby-load-path - a vector of file paths, containing the locations of puppet source code.

    * :gem-home - The location that JRuby gems are stored

    * :compile-mode - The value to use for JRuby's CompileMode setting.  Legal
        values are `:jit`, `:force`, and `:off`.  Defaults to `:off`.

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

    * :max-active-instances - The maximum number of JRubyPuppet instances that
        will be pooled.

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

    * :use-legacy-auth-conf - Whether to use the legacy core Puppet auth.conf
        (true) or trapperkeeper-authorization (false) to authorize requests
        being made to core Puppet endpoints."
  {:ruby-load-path [schema/Str]
   :gem-home schema/Str
   :master-conf-dir (schema/maybe schema/Str)
   :master-code-dir (schema/maybe schema/Str)
   :master-var-dir (schema/maybe schema/Str)
   :master-run-dir (schema/maybe schema/Str)
   :master-log-dir (schema/maybe schema/Str)
   :http-client-ssl-protocols [schema/Str]
   :http-client-cipher-suites [schema/Str]
   :http-client-connect-timeout-milliseconds schema/Int
   :http-client-idle-timeout-milliseconds schema/Int
   :max-requests-per-instance schema/Int
   :use-legacy-auth-conf schema/Bool})

(defn jruby-main-instance?
  [x]
  (instance? Main x))

(defn jruby-main-status-instance?
  [x]
  (instance? Main$Status x))

(defn jruby-instance-config?
  [x]
  (instance? RubyInstanceConfig x))
