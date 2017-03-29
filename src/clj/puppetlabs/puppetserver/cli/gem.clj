(ns puppetlabs.puppetserver.cli.gem
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]))

(defn gem-run!
  [config args]
  (let [jruby-config (jruby-puppet-core/initialize-and-create-jruby-config config)]
    (jruby-core/cli-run! jruby-config "gem" args)))

(defn -main
  [& args]
  (cli/jruby-run gem-run! args))
