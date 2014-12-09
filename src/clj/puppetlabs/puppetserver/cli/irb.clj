(ns puppetlabs.puppetserver.cli.irb
  (:import (org.jruby Main RubyInstanceConfig)
           (java.util HashMap))
  (:require [puppetlabs.puppetserver.cli.subcommand :as cli]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]))

(defn run!
  [config args]
  (let [load-path-args    (map #(str "-I" %)
                            (get-in config [:os-settings
                                            :ruby-load-path]))
        args              (into-array String
                                     (concat ["-e"
                                              (str "load 'META-INF/jruby.home/bin/irb'")
                                              (str "-I" jruby-puppet/ruby-code-dir)
                                              "--"]
                                             load-path-args
                                             args))
        jruby-config      (RubyInstanceConfig.)
        env-with-gem-home (doto (-> jruby-config (.getEnvironment) (HashMap.))
                            (.put "GEM_HOME"
                                  (get-in config
                                          [:jruby-puppet :gem-home])))]
    (.setEnvironment jruby-config env-with-gem-home)
    (-> (Main. jruby-config)
        (.run args))))

(defn -main
  [& args]
  (cli/run run! args))
