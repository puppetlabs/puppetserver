(ns puppetlabs.puppetserver.cli.ruby
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]))

(defn ruby-run!
  [config args]
  (let [jruby-config (jruby-puppet-core/initialize-and-create-jruby-config config)]
    (jruby-core/cli-ruby! jruby-config args)))

(defn -main
  [& args]
  (cli/jruby-run ruby-run! args))
