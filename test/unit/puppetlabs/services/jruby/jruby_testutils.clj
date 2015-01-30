(ns puppetlabs.services.jruby.jruby-testutils
  (:import (com.puppetlabs.puppetserver JRubyPuppet JRubyPuppetResponse)
           (org.jruby.embed ScriptingContainer LocalContextScope))
  (:require [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.services.puppet-profiler.puppet-profiler-core :as profiler-core]
            [me.raynes.fs :as fs]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"])

(def conf-dir "./target/master-conf")

(def gem-home "./target/jruby-gem-home")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test fixtures

(defn with-puppet-conf
  "This function returns a test fixture that will copy a specified puppet.conf
  file into the provided location for testing, and then delete it after the
  tests have completed. If no destination dir is provided then the puppet.coonf
  file is copied to the default location of './target/master-conf'."
  ([puppet-conf-file]
   (with-puppet-conf puppet-conf-file conf-dir))
  ([puppet-conf-file dest-dir]
   (let [target-path (fs/file dest-dir "puppet.conf")]
     (fn [f]
       (fs/copy+ puppet-conf-file target-path)
       (try
         (f)
         (finally
           (fs/delete target-path)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test util functions

(defn jruby-puppet-tk-config
  "Create a JRubyPuppet pool config with the given pool config.  Suitable for use
  in bootstrapping trapperkeeper."
  [pool-config]
  {:os-settings  {:ruby-load-path ruby-load-path}
   :product     {:name "puppet-server"
                 :update-server-url "http://localhost:11111"}
   :jruby-puppet pool-config
   :certificate-authority {:certificate-status {:client-whitelist []}}})

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

(defn create-pool-instance
  ([]
   (create-pool-instance (jruby-puppet-config 1)))
  ([config]
   (let [pool (jruby-core/instantiate-free-pool 1)]
     (jruby-core/create-pool-instance! pool 1 config default-profiler))))

(defn create-mock-jruby-instance
  "Creates a mock implementation of the JRubyPuppet interface."
  []
  (reify JRubyPuppet
    (handleRequest [_ _]
      (JRubyPuppetResponse. 0 nil nil nil))
    (getSetting [_ _]
      (Object.))))

(defn create-mock-pool-instance
  [pool id _ _]
  (let [instance (jruby-core/map->JRubyPuppetInstance
                   {:pool                 pool
                    :id                   id
                    :state                (atom {:request-count 0})
                    :jruby-puppet         (create-mock-jruby-instance)
                    :scripting-container  (ScriptingContainer. LocalContextScope/SINGLETHREAD)
                    :environment-registry (puppet-env/environment-registry)})]
    (.put pool instance)
    instance))

(defn mock-pool-instance-fixture
  "Test fixture which changes the behavior of the JRubyPool to create
  mock JRubyPuppet instances."
  [f]
  (with-redefs
    [jruby-core/create-pool-instance! create-mock-pool-instance]
    (f)))

(defn drain-pool
  "Drains the JRubyPuppet pool and returns each instance in a vector."
  [pool size]
  (mapv (fn [_] (jruby-core/borrow-from-pool pool)) (range size)))

(defn fill-drained-pool
  "Returns a list of JRubyPuppet instances back to their pool."
  [instance-list]
  (doseq [instance instance-list]
    (jruby-core/return-to-pool instance)))

(defn reduce-over-jrubies!
  "Utility function; takes a JRuby pool and size, and a function f from integer
  to string.  For each JRuby instance in the pool, f will be called, passing in
  an integer offset into the jruby array (0..size), and f is expected to return
  a string containing a script to run against the jruby instance.

  Returns a vector containing the results of executing the scripts against the
  JRuby instances."
  [pool size f]
  (let [jrubies (drain-pool pool size)
        result  (reduce
                  (fn [acc jruby-offset]
                    (let [sc (:scripting-container (nth jrubies jruby-offset))
                          script (f jruby-offset)
                          result (.runScriptlet sc script)]
                      (conj acc result)))
                  []
                  (range size))]
    (fill-drained-pool jrubies)
    result))

