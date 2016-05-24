(ns puppetlabs.puppetserver.cli.gem
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]))

(defn gem-run!
  [config args]
  (let [jruby-config (jruby-core/initialize-config (jruby-puppet-core/extract-jruby-config (:jruby-puppet config)))]
    (jruby-core/cli-run! jruby-config "gem" args)))

(defn -main
  [& args]
  (cli/run gem-run! args))
