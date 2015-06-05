(ns puppetlabs.puppetserver.cli.ruby
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(defn -main
  [& args]
  (cli/run jruby-core/cli-ruby! args))
