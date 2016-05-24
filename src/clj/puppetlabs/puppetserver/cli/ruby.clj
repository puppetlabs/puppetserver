(ns puppetlabs.puppetserver.cli.ruby
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]))

(defn ruby-run!
  [config args]
  (let [jruby-config (jruby-core/initialize-config (jruby-puppet-core/extract-jruby-config (:jruby-puppet config)))]
    (jruby-core/cli-ruby! (jruby-core/initialize-config jruby-config) args)))

(defn -main
  [& args]
  (cli/run ruby-run! args))
