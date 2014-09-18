(ns puppetlabs.services.jruby.testutils
  (:import (com.puppetlabs.puppetserver JRubyPuppet JRubyPuppetResponse))
  (:require [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :as profiler-core]
            [me.raynes.fs :as fs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"])

(def conf-dir "./target/master-conf")

(def gem-home "./target/jruby-gem-home")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test fixtures

(defn with-puppet-conf
  "This function returns a test fixture that will copy a specified puppet.conf
  file into the appropriate location for testing, and then delete it after the
  tests have completed."
  [puppet-conf-file]
  (let [target-path (fs/file conf-dir "puppet.conf")]
    (fn [f]
      (fs/copy+ puppet-conf-file target-path)
      (try
        (f)
        (finally
          (fs/delete target-path))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test util functions

(defn jruby-puppet-tk-config
  "Create a JRubyPuppet pool config with the given pool config.  Suitable for use
  in bootstrapping trapperkeeper."
  [pool-config]
  {:os-settings  {:ruby-load-path ruby-load-path}
   :jruby-puppet pool-config})

(defn jruby-puppet-config
  "Create a JRubyPuppet pool config. If `pool-size` is provided then the
  JRubyPuppet pool size with be included with the config, otherwise no size
  will be specified."
  ([]
   {:ruby-load-path  ruby-load-path
    :gem-home        gem-home
    :master-conf-dir conf-dir})
  ([pool-size]
   (assoc (jruby-puppet-config) :max-active-instances pool-size)))

(def default-config-no-size
  (jruby-puppet-config))

(def default-profiler
  nil)

(defn create-jruby-instance
  ([]
   (create-jruby-instance (jruby-puppet-config 1)))
  ([config]
   (jruby-core/create-jruby-instance config default-profiler)))

(defn create-mock-jruby-instance
  "Creates a mock implementation of the JRubyPuppet interface."
  [& _]
  (reify JRubyPuppet
    (handleRequest [_ _]
      (JRubyPuppetResponse. 0 nil nil nil))
    (getSetting [_ _]
      (Object.))))

(defn mock-jruby-fixture
  "Test fixture which changes the behavior of the JRubyPool to create
  mock JRubyPuppet instances."
  [f]
  (with-redefs
    [jruby-core/create-jruby-instance create-mock-jruby-instance]
    (f)))
