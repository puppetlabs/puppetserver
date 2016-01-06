(ns puppetlabs.puppetserver.cli.irb
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(defn run!
  [input args]
  (jruby-core/cli-run! (:config input) "irb" args))

(defn -main
  [& args]
  (cli/run run! args))
