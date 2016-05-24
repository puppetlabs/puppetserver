(ns puppetlabs.puppetserver.cli.irb
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]))

(defn irb-run!
  [config args]
  (let [jruby-config (jruby-core/initialize-config (jruby-puppet-core/extract-jruby-config (:jruby-puppet config)))]
    (jruby-core/cli-run! jruby-config "irb" args)))

(defn -main
  [& args]
  (cli/run irb-run! args))
