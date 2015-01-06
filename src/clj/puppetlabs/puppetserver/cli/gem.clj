(ns puppetlabs.puppetserver.cli.gem
  (:import (org.jruby.embed ScriptingContainer))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]))

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
