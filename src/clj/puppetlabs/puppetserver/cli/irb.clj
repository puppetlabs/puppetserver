(ns puppetlabs.puppetserver.cli.irb
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]))

(defn irb-run!
  [config args]
  (let [jruby-config (jruby-puppet-core/initialize-and-create-jruby-config config)]
    (jruby-core/cli-run! jruby-config "irb" args)))

(defn -main
  [& args]
  (cli/jruby-run irb-run! args))
