(ns puppetlabs.services.jruby.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :refer :all]
            [puppetlabs.services.jruby.jruby-testutils :as testutils]))

(use-fixtures :once
              (testutils/with-puppet-conf
                "./dev-resources/puppetlabs/services/jruby/jruby_interpreter_test/puppet.conf"))

(deftest create-jruby-instance-test

  (testing "Var dir is not required (it will be read from puppet.conf)"
    (let [vardir (-> (testutils/jruby-puppet-config)
                     (dissoc :master-var-dir)
                     (testutils/create-pool-instance)
                     (:jruby-puppet)
                     (.getSetting "vardir"))]
      (is (= (fs/absolute-path "target/master-var-jruby-int-test") vardir))))

  (testing "Directories can be configured programatically
            (and take precedence over puppet.conf)"
    (let [puppet (-> {:ruby-load-path  testutils/ruby-load-path
                      :gem-home        testutils/gem-home
                      :master-conf-dir testutils/conf-dir
                      :master-code-dir testutils/code-dir
                      :master-var-dir  testutils/var-dir
                      :master-run-dir  testutils/run-dir
                      :master-log-dir  testutils/log-dir}
                     (testutils/create-pool-instance)
                     (:jruby-puppet))]
      (are [setting expected] (= (-> expected
                                     (fs/normalized-path)
                                     (fs/absolute-path))
                                 (.getSetting puppet setting))
           "confdir" testutils/conf-dir
           "codedir" testutils/code-dir
           "vardir" testutils/var-dir
           "rundir" testutils/run-dir
           "logdir" testutils/log-dir)))

  (testing "Settings from Ruby Puppet are available"
    (let [jruby-puppet (-> (testutils/jruby-puppet-config)
                           (testutils/create-pool-instance)
                           (:jruby-puppet))]
      (testing "Various data types"
        (is (= "0.0.0.0" (.getSetting jruby-puppet "bindaddress")))
        (is (= 8140 (.getSetting jruby-puppet "masterport")))
        (is (= false (.getSetting jruby-puppet "onetime")))))))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (create-scripting-container
                              testutils/ruby-load-path
                              testutils/gem-home)
          jruby-env (.runScriptlet jruby-interpreter "ENV")]

      ;; $HOME and $PATH are left in by `jruby-puppet-env`
      (is (= #{"HOME" "JARS_NO_REQUIRE" "PATH" "GEM_HOME"}
             (set (keys jruby-env)))))))
