(ns puppetlabs.puppetserver.cli.irb
  (:import (org.jruby Main RubyInstanceConfig CompatVersion)
           (java.util HashMap))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]))

(defn new-jruby-main
  [config]
  (let [jruby-config (RubyInstanceConfig.)
        gem-home     (get-in config [:jruby-puppet :gem-home])
        env          (doto (HashMap. (.getEnvironment jruby-config))
                       (.put "GEM_HOME" gem-home)
                       (.put "JARS_NO_REQUIRE" "true")
                       (.put "JARS_REQUIRE" "false"))
        jruby-home   (.getJRubyHome jruby-config)
        load-path    (->> (get-in config [:jruby-puppet :ruby-load-path])
                       (cons jruby-internal/ruby-code-dir)
                       (cons (str jruby-home "/lib/ruby/1.9"))
                       (cons (str jruby-home "/lib/ruby/shared"))
                       (cons (str jruby-home "/lib/ruby/1.9/site_ruby")))]
    (doto jruby-config
      (.setEnvironment env)
      (.setLoadPaths load-path)
      (.setCompatVersion (CompatVersion/RUBY1_9)))
    (Main. jruby-config)))

(defn run!
  [config ruby-args]
  (doto (new-jruby-main config)
    (.run (into-array String
            (concat
              ["-rjar-dependencies"
               "-e"
               "load 'META-INF/jruby.home/bin/irb'"
               "--"]
              ruby-args)))))

(defn -main
  [& args]
  (cli/run run! args))
