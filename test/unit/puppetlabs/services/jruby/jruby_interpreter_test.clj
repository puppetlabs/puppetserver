(ns puppetlabs.services.jruby.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.jruby-testutils :as testutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [puppetlabs.services.jruby.jruby-puppet-core :refer :all
             :as core]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]))

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
                "./dev-resources/puppetlabs/services/jruby/jruby_interpreter_test/puppet.conf"))

(deftest create-jruby-instance-test

  (testing "Var dir is not required."
    (let [config        {:ruby-load-path  testutils/ruby-load-path
                         :gem-home        testutils/gem-home
                         :master-conf-dir testutils/conf-dir}
          pool          (instantiate-free-pool 1)
          pool-instance (create-pool-instance pool 1 config testutils/default-profiler)
          jruby-puppet  (:jruby-puppet pool-instance)
          var-dir       (.getSetting jruby-puppet "vardir")]
      (is (not (nil? var-dir)))))

  (testing "Settings from Ruby Puppet are available"
    (let [temp-dir      (.getAbsolutePath (ks/temp-dir))
          config        (assoc (testutils/jruby-puppet-config)
                          :master-var-dir temp-dir)
          pool-instance (testutils/create-pool-instance config)
          jruby-puppet  (:jruby-puppet pool-instance)]
      (is (= "0.0.0.0" (.getSetting jruby-puppet "bindaddress")))
      (is (= 8140 (.getSetting jruby-puppet "masterport")))
      (is (= false (.getSetting jruby-puppet "onetime")))
      (is (= (fs/absolute-path temp-dir)
             (.getSetting jruby-puppet "vardir")))

      (is (= (-> (:master-conf-dir config)
                 fs/normalized-path
                 fs/absolute-path)
             (.getSetting jruby-puppet "confdir"))))))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (create-scripting-container
                              jruby-testutils/ruby-load-path
                              jruby-testutils/gem-home)
          jruby-env (.runScriptlet jruby-interpreter "ENV")]

      ; $HOME and $PATH are left in by `jruby-puppet-env`
      (is (= #{"HOME" "PATH" "GEM_HOME"} (set (keys jruby-env)))))))
