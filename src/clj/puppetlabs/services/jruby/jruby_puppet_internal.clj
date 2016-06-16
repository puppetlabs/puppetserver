(ns puppetlabs.services.jruby.jruby-puppet-internal
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal])
  (:import (java.util HashMap)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn ^:always-validate managed-load-path :- [schema/Str]
  "Return a list of ruby LOAD_PATH directories built from the
  user-configurable ruby-load-path setting of the jruby-puppet configuration."
  [ruby-load-path :- [schema/Str]]
  (cons ruby-code-dir ruby-load-path))

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
