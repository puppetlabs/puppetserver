(ns puppetlabs.puppetserver.cli.irb
  (:import (org.jruby Main RubyInstanceConfig CompatVersion))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]))

(defn run!
  [config args]
  (let [args         (into-array String
                       (concat ["-e"
                                "load 'META-INF/jruby.home/bin/irb'"
                                "--"]
                               args))
        jruby-config (RubyInstanceConfig.)
        initial-env  (.getEnvironment jruby-config)
        jruby-home   (.getJRubyHome jruby-config)
        load-path    (->> (get-in config [:os-settings :ruby-load-path])
                          (cons jruby-puppet/ruby-code-dir)
                          (cons (str jruby-home "/lib/ruby/1.9"))
                          (cons (str jruby-home "/lib/ruby/shared"))
                          (cons (str jruby-home "/lib/ruby/1.9/site_ruby")))]
    (doto jruby-config
      (.setEnvironment (cli/environment config initial-env))
      (.setLoadPaths load-path)
      (.setCompatVersion (CompatVersion/RUBY1_9)))
    (-> (Main. jruby-config)
        (.run args))))

(defn -main
  [& args]
  (cli/run run! args))
