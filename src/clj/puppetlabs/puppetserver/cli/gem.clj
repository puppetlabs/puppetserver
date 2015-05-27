(ns puppetlabs.puppetserver.cli.gem
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(defn run!
  [config args]
  (jruby-core/cli-run! config "gem" args))

(defn -main
  [& args]
  (cli/run run! args))
