(ns puppetlabs.puppetserver.cli.gem
  (:import (org.jruby.embed ScriptingContainer))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]))

(defn run!
  [config args]
  (doto (ScriptingContainer.)
    (.setArgv (into-array String args))
    (.setEnvironment
      (hash-map
        "http_proxy" (System/getenv "http_proxy")
        "https_proxy" (System/getenv "https_proxy")
        "GEM_HOME" (get-in config [:jruby-puppet :gem-home])
        "JARS_NO_REQUIRE" "true"))
    (.runScriptlet "load 'META-INF/jruby.home/bin/gem'")))

(defn -main
  [& args]
  (cli/run run! args))
