(ns puppetlabs.puppetserver.cli.irb
  (:import (org.jruby Main RubyInstanceConfig CompatVersion)
           (java.util HashMap))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]))

(defn run!
  [config args]
  (let [args         (into-array String
                       (concat ["-e"
                                "load 'META-INF/jruby.home/bin/irb'"
                                "--"]
                               args))
        jruby-config (RubyInstanceConfig.)
        gem-home     (get-in config [:jruby-puppet :gem-home])
        env          (doto (HashMap. (.getEnvironment jruby-config))
                       (.put "GEM_HOME" gem-home)
                       (.put "JARS_NO_REQUIRE" "true"))
        jruby-home   (.getJRubyHome jruby-config)
        load-path    (->> (get-in config [:os-settings :ruby-load-path])
                          (cons jruby-internal/ruby-code-dir)
                          (cons (str jruby-home "/lib/ruby/1.9"))
                          (cons (str jruby-home "/lib/ruby/shared"))
                          (cons (str jruby-home "/lib/ruby/1.9/site_ruby")))]
    (doto jruby-config
      (.setEnvironment env)
      (.setLoadPaths load-path)
      (.setCompatVersion (CompatVersion/RUBY1_9)))
    (-> (Main. jruby-config)
        (.run args))))

(defn -main
  [& args]
  (cli/run run! args))
