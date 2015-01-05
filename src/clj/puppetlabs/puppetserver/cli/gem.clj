(ns puppetlabs.puppetserver.cli.gem
  (:import (org.jruby.embed ScriptingContainer))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]))

(defn cli-gem-environment
  "Return a map representing the environment.  Environment variables
  puppetserver needs are enforced while all other variables are preserved.
  By design this function only modifies environment variables puppetserver
  cares about.  The underlying principle is that a process should preserve as
  much of the environment as possible in an effort to get out of the end users
  way.  This includes PATH which should be fully managed elsewhere, e.g. the
  service management framework."
  [config initial-env]
  (let [clojure-map-env (into {} initial-env)]
    (assoc clojure-map-env
           "GEM_HOME" (get-in config [:jruby-puppet :gem-home])
           "JARS_NO_REQUIRE" "true")))

(defn run!
  [config args]
  (let [container (new ScriptingContainer)
        initial-env (.getEnvironment container)]
    (doto container
      (.setArgv (into-array String args))
      (.setEnvironment (cli-gem-environment config initial-env))
      (.runScriptlet "load 'META-INF/jruby.home/bin/gem'"))))

(defn -main
  [& args]
  (cli/run run! args))
