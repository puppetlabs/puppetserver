(ns puppetlabs.puppetserver.cli.irb
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(defn irb-run!
  [config args]
  (jruby-core/cli-run! config "irb" args))

(defn -main
  [& args]
  (cli/run irb-run! args))
