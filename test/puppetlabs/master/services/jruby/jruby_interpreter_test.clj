(ns puppetlabs.master.services.jruby.jruby-interpreter-test
  (:require [clojure.test :refer :all]
            [puppetlabs.master.services.jruby.testutils :as testutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [puppetlabs.master.services.jruby.jruby-puppet-core :refer :all
                                                                :as core]))

(deftest create-jruby-instance-test
  (let [config (testutils/jruby-puppet-config-with-prod-env 1)]
    (testing "Var dir is optional"
      (is (not (nil? (create-jruby-instance config))))))

  (let [temp-dir (ks/temp-dir)
        config   (assoc
                     (testutils/jruby-puppet-config-with-prod-env 1)
                   :master-var-dir temp-dir)]
    (testing "Omitting load-path results in an Exception."
      (is (thrown-with-msg? Exception
                            #"JRuby service missing config value 'load-path'"
                            (create-jruby-instance (dissoc config :load-path)))))

    (testing "Settings from Ruby Puppet are available"
      (let [jruby-puppet (testutils/create-jruby-instance config)]
        (is (= "0.0.0.0" (.getSetting jruby-puppet "bindaddress")))
        (is (= 8140 (.getSetting jruby-puppet "masterport")))
        (is (= false (.getSetting jruby-puppet "onetime")))
        (is (= (fs/absolute-path temp-dir)
               (.getSetting jruby-puppet "vardir")))

        (is (= (-> (:master-conf-dir config)
                   fs/normalized-path
                   fs/absolute-path )
               (.getSetting jruby-puppet "confdir")))))))

(deftest jruby-env-vars
  (testing "the environment used by the JRuby interpreters"
    (let [jruby-interpreter (create-scripting-container
                              ["./ruby/puppet/lib" "./ruby/facter/lib"])
          jruby-env (.runScriptlet jruby-interpreter "ENV")]

      ; $HOME and $PATH are left in by `jruby-puppet-env`
      (is (= #{"HOME" "PATH"} (set (keys jruby-env)))))))
