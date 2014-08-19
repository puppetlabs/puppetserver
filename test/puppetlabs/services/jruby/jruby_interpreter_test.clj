(ns puppetlabs.services.jruby.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.testutils :as testutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [puppetlabs.services.jruby.jruby-puppet-core :refer :all
                                                                :as core]))

(deftest create-jruby-instance-test

  (testing "Var dir is not required."
    (let [config  { :load-path       testutils/load-path
                    :master-conf-dir testutils/conf-dir }
          jruby   (create-jruby-instance config testutils/default-profiler)
          var-dir (.getSetting jruby "vardir")]
      (is (not (nil? var-dir)))))

  (testing "Settings from Ruby Puppet are available"
    (let [temp-dir      (ks/temp-dir)
          config        (assoc (testutils/jruby-puppet-config-with-prod-env)
                          :master-var-dir temp-dir)
          jruby-puppet  (testutils/create-jruby-instance config)]
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
                              ["./ruby/puppet/lib" "./ruby/facter/lib"])
          jruby-env (.runScriptlet jruby-interpreter "ENV")]

      ; $HOME and $PATH are left in by `jruby-puppet-env`
      (is (= #{"HOME" "PATH"} (set (keys jruby-env)))))))
