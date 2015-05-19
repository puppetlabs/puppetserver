(ns puppetlabs.puppetserver.cli.ruby
  (:import (org.jruby Main RubyInstanceConfig CompatVersion)
           (java.util HashMap))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]))

(defn new-jruby-main
  [config]
  (let [load-path    (->> (get-in config [:jruby-puppet :ruby-load-path])
                          (cons jruby-internal/ruby-code-dir))
        gem-home     (get-in config [:jruby-puppet :gem-home])
        jruby-config (new RubyInstanceConfig)
        env          (doto (HashMap. (.getEnvironment jruby-config))
                       (.put "GEM_HOME" gem-home)
                       (.put "JARS_NO_REQUIRE" "true")
                       (.put "JARS_REQUIRE" "false"))]
    (doto jruby-config
      (.setEnvironment env)
      (.setLoadPaths load-path)
      (.setCompatVersion (CompatVersion/RUBY1_9)))
    (Main. jruby-config)))

(defn run!
  [config ruby-args]
  (doto (new-jruby-main config)
    (.run (into-array String ["-e" "require 'jar-dependencies'"]))
    (.run (into-array String ruby-args))))

(defn -main
  [& args]
  (cli/run run! args))
