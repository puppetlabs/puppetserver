(ns puppetlabs.services.jruby.jruby-puppet-internal
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.kitchensink.core :as ks])
  (:import (java.util HashMap)
           (org.jruby CompatVersion Main RubyInstanceConfig RubyInstanceConfig$CompileMode)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def ruby-code-dir
  "The name of the directory containing the ruby code in this project.

  This directory is relative to `src/ruby` and works from source because the
  `src/ruby` directory is defined as a resource in `project.clj` which places
  the directory on the classpath which in turn makes the directory available on
  the JRuby load path.  Similarly, this works from the uberjar because this
  directory is placed into the root of the jar structure which is on the
  classpath.

  See also:  http://jruby.org/apidocs/org/jruby/runtime/load/LoadService.html"
  "puppetserver-lib")

(def compat-version
  "The JRuby compatibility version to use for all ruby components, e.g. the
  master service and CLI tools."
  (CompatVersion/RUBY1_9))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn get-system-env :- jruby-schemas/EnvPersistentMap
  "Same as System/getenv, but returns a clojure persistent map instead of a
  Java unmodifiable map."
  []
  (into {} (System/getenv)))

(schema/defn ^:always-validate managed-environment :- jruby-schemas/EnvMap
  "The environment variables that should be passed to the Puppet JRuby
  interpreters.

  We don't want them to read any ruby environment variables, like $RUBY_LIB or
  anything like that, so pass it an empty environment map - except - Puppet
  needs HOME and PATH for facter resolution, so leave those, along with GEM_HOME
  which is necessary for third party extensions that depend on gems.

  We need to set the JARS..REQUIRE variables in order to instruct JRuby's
  'jar-dependencies' to not try to load any dependent jars.  This is being
  done specifically to avoid JRuby trying to load its own version of Bouncy
  Castle, which may not the same as the one that 'puppetlabs/ssl-utils'
  uses. JARS_NO_REQUIRE was the legacy way to turn off jar loading but is
  being phased out in favor of JARS_REQUIRE.  As of JRuby 1.7.20, only
  JARS_NO_REQUIRE is honored.  Setting both of those here for forward
  compatibility."
  [env :- jruby-schemas/EnvMap
   gem-home :- schema/Str]
  (let [whitelist ["HOME" "PATH"]
        clean-env (select-keys env whitelist)]
    (assoc clean-env
      "GEM_HOME" gem-home
      "JARS_NO_REQUIRE" "true"
      "JARS_REQUIRE" "false")))

(schema/defn ^:always-validate managed-load-path :- [schema/Str]
  "Return a list of ruby LOAD_PATH directories built from the
  user-configurable ruby-load-path setting of the jruby-puppet configuration."
  [ruby-load-path :- [schema/Str]]
  (cons ruby-code-dir ruby-load-path))

(schema/defn ^:always-validate get-compile-mode :- RubyInstanceConfig$CompileMode
  [config-compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (case config-compile-mode
    :jit RubyInstanceConfig$CompileMode/JIT
    :force RubyInstanceConfig$CompileMode/FORCE
    :off RubyInstanceConfig$CompileMode/OFF))

(schema/defn ^:always-validate init-jruby-config :- jruby-schemas/ConfigurableJRuby
  "Applies configuration to a JRuby... thing.  See comments in `ConfigurableJRuby`
  schema for more details."
  [jruby-config :- jruby-schemas/ConfigurableJRuby
   ruby-load-path :- [schema/Str]
   gem-home :- schema/Str
   compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (doto jruby-config
    (.setLoadPaths (managed-load-path ruby-load-path))
    (.setCompatVersion compat-version)
    (.setCompileMode (get-compile-mode compile-mode))
    (.setEnvironment (managed-environment (get-system-env) gem-home))))

(schema/defn ^:always-validate config->puppet-config :- HashMap
  "Given the raw jruby-puppet configuration section, return a
  HashMap with the configuration necessary for ruby Puppet."
  [config :- jruby-schemas/JRubyPuppetConfig]
  (let [puppet-config (new HashMap)]
    (doseq [[setting dir] [[:master-conf-dir "confdir"]
                           [:master-code-dir "codedir"]
                           [:master-var-dir "vardir"]
                           [:master-run-dir "rundir"]
                           [:master-log-dir "logdir"]]]
      (if-let [value (get config setting)]
        (.put puppet-config dir (ks/absolute-path value))))
    puppet-config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate new-main :- jruby-schemas/JRubyMain
  "Return a new JRuby Main instance which should only be used for CLI purposes,
  e.g. for the ruby, gem, and irb subcommands.  Internal core services should
  use `create-scripting-container` instead of `new-main`."
  [config :- jruby-schemas/JRubyPuppetConfig]
  (let [{:keys [ruby-load-path gem-home compile-mode]} config
        jruby-config (init-jruby-config
                      (RubyInstanceConfig.)
                      ruby-load-path
                      gem-home
                      compile-mode)]
    (Main. jruby-config)))
