(ns puppetlabs.services.jruby.jruby-testutils
  (:require [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [me.raynes.fs :as fs]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [schema.core :as schema]
            [clojure.tools.logging :as log])
  (:import (com.puppetlabs.puppetserver JRubyPuppet JRubyPuppetResponse PuppetProfiler)
           (org.jruby.embed ScriptingContainer LocalContextScope)
           (puppetlabs.services.jruby.jruby_puppet_schemas JRubyPuppetInstance)
           (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib" "./ruby/hiera/lib"])
(def gem-home "./target/jruby-gem-home")

(def conf-dir "./target/master-conf")
(def code-dir "./target/master-code")
(def var-dir "./target/master-var")
(def run-dir "./target/master-var/run")
(def log-dir "./target/master-var/log")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test fixtures

(defn with-puppet-conf
  "This function returns a test fixture that will copy a specified puppet.conf
  file into the provided location for testing, and then delete it after the
  tests have completed. If no destination dir is provided then the puppet.conf
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
  in bootstrapping trapperkeeper (in other words, returns a representation of the
  config that matches what would be read directly from the config files on disk,
  as opposed to a version that has been processed and transformed to comply
  with the JRubyPuppetConfig schema)."
  [pool-config]
  {:product       {:name              "puppet-server"
                   :update-server-url "http://localhost:11111"}
   :jruby-puppet  pool-config
   :authorization {:version 1
                   :rules [{:match-request {:path "/" :type "path"}
                            :allow "*"
                            :sort-order 1
                            :name "allow all"}]}})

(schema/defn ^:always-validate
  jruby-puppet-config :- jruby-schemas/JRubyPuppetConfig
  "Create a JRubyPuppetConfig for testing. The optional map argument `options` may
  contain a map, which, if present, will be merged into the final JRubyPuppetConfig
  map.  (This function differs from `jruby-puppet-tk-config` in
  that it returns a map that complies with the JRubyPuppetConfig schema, which
  differs slightly from the raw format that would be read from config files
  on disk.)"
  ([]
    (jruby-core/initialize-config
      {:jruby-puppet
       {:ruby-load-path  ruby-load-path
        :gem-home        gem-home
        :master-conf-dir conf-dir
        :master-code-dir code-dir
        :master-var-dir  var-dir
        :master-run-dir  run-dir
        :master-log-dir  log-dir
        :use-legacy-auth-conf false}}))
  ([options]
   (merge (jruby-puppet-config) options)))

(def default-profiler
  nil)

(defn default-shutdown-fn
  [f]
  (f))

(def default-flush-fn
  identity)

(defn create-pool-instance
  ([]
   (create-pool-instance (jruby-puppet-config {:max-active-instances 1})))
  ([config]
   (let [pool (jruby-internal/instantiate-free-pool 1)]
     (jruby-internal/create-pool-instance! pool 1 config default-flush-fn default-profiler))))

(defn create-mock-jruby-instance
  "Creates a mock implementation of the JRubyPuppet interface."
  []
  (reify JRubyPuppet
    (handleRequest [_ _]
      (JRubyPuppetResponse. 0 nil nil nil))
    (getSetting [_ _]
      (Object.))
    (terminate [_]
      (log/info "Terminating Master"))))

(schema/defn ^:always-validate
  create-mock-pool-instance :- JRubyPuppetInstance
  [pool :- jruby-schemas/pool-queue-type
   id :- schema/Int
   config :- jruby-schemas/JRubyPuppetConfig
   flush-instance-fn :- IFn
   _ :- (schema/maybe PuppetProfiler)]
  (let [instance (jruby-schemas/map->JRubyPuppetInstance
                   {:pool                 pool
                    :id                   id
                    :max-requests         (:max-requests-per-instance config)
                    :flush-instance-fn    flush-instance-fn
                    :state                (atom {:borrow-count 0})
                    :jruby-puppet         (create-mock-jruby-instance)
                    :scripting-container  (ScriptingContainer. LocalContextScope/SINGLETHREAD)
                    :environment-registry (puppet-env/environment-registry)})]
    (.register pool instance)
    instance))

(defn mock-pool-instance-fixture
  "Test fixture which changes the behavior of the JRubyPool to create
  mock JRubyPuppet instances."
  [f]
  (with-redefs
    [jruby-internal/create-pool-instance! create-mock-pool-instance]
    (f)))

(defmacro with-mock-pool-instance-fixture
  [& body]
  `(let [f# (fn [] (do ~@body))]
     (mock-pool-instance-fixture f#)))

(defn drain-pool
  "Drains the JRubyPuppet pool and returns each instance in a vector."
  [pool-context size]
  (mapv (fn [_] (jruby-core/borrow-from-pool pool-context :test [])) (range size)))

(defn fill-drained-pool
  "Returns a list of JRubyPuppet instances back to their pool."
  [instance-list]
  (doseq [instance instance-list]
    (jruby-core/return-to-pool instance :test [])))

(defn reduce-over-jrubies!
  "Utility function; takes a JRuby pool and size, and a function f from integer
  to string.  For each JRuby instance in the pool, f will be called, passing in
  an integer offset into the jruby array (0..size), and f is expected to return
  a string containing a script to run against the jruby instance.

  Returns a vector containing the results of executing the scripts against the
  JRuby instances."
  [pool-context size f]
  (let [jrubies (drain-pool pool-context size)
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

(defn wait-for-jrubies
  "Wait for all jrubies to land in the JRubyPuppetService's pool"
  [app]
  (let [pool-context (-> app
                         (tk-app/app-context)
                         deref
                         :JRubyPuppetService
                         :pool-context)
        num-jrubies (-> pool-context
                        :pool-state
                        deref
                        :size)]
    (while (< (count (jruby-core/registered-instances pool-context))
              num-jrubies)
      (Thread/sleep 100))))
