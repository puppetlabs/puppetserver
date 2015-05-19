(ns puppetlabs.puppetserver.cli.gem
  (:import (org.jruby.embed ScriptingContainer))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]))

(defn run!
  [config args]
  (doto (ScriptingContainer.)
    (.setArgv (into-array String args))
    (.setEnvironment
      (hash-map
        "GEM_HOME" (get-in config [:jruby-puppet :gem-home])
        "JARS_NO_REQUIRE" "true"
        "JARS_REQUIRE" "false"))
    (.runScriptlet "require 'jar-dependencies'")
    (.runScriptlet "load 'META-INF/jruby.home/bin/gem'")))

(defn -main
  [& args]
  (cli/run run! args))
