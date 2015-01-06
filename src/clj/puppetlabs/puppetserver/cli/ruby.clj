(ns puppetlabs.puppetserver.cli.ruby
  (:import (org.jruby Main RubyInstanceConfig CompatVersion))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]))

(defn new-jruby-main
  [config]
  (let [load-path    (->> (get-in config [:os-settings :ruby-load-path])
                          (cons jruby-puppet/ruby-code-dir))
        jruby-config (new RubyInstanceConfig)
        initial-env  (.getEnvironment jruby-config)]
    (doto jruby-config
      (.setEnvironment (cli/environment config initial-env))
      (.setLoadPaths load-path)
      (.setCompatVersion (CompatVersion/RUBY1_9)))
    (Main. jruby-config)))

(defn run!
  [config ruby-args]
  (-> (new-jruby-main config)
    (.run (into-array String ruby-args))))

(defn -main
  [& args]
  (cli/run run! args))
