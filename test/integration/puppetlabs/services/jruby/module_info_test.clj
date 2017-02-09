(ns puppetlabs.services.jruby.module-info-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]))

(defn gen-modules
  [[mod-dir manifests]]
  (let [modules-dir (fs/file mod-dir "modules")]
    (ks/mkdirs! modules-dir)
    (doseq [manifest manifests]
      (let [module-dir (fs/file modules-dir manifest)
            metadata-json {"name" manifest
                           "version" "1.0.0"
                           "author" "Puppet"
                           "license" "apache"
                           "dependencies" []
                           "source" "https://github.com/puppetlabs"}]
        (ks/mkdirs! (fs/file module-dir))
        (spit (fs/file module-dir "metadata.json")
              (cheshire/generate-string metadata-json))))))

(defn create-env
  [[env-dir manifests]]
  (testutils/create-env-conf env-dir "")
  (gen-modules [env-dir manifests]))

(defn roundtrip-via-json
  [obj]
  (-> obj
      (cheshire/generate-string)
      (cheshire/parse-string)))

(deftest ^:integration module-info-test
  (testing "module info properly enumerated for"
    (let [code-dir (ks/temp-dir)
          conf-dir (ks/temp-dir)
          config (jruby-testutils/jruby-puppet-tk-config
                  (jruby-testutils/jruby-puppet-config
                   {:master-code-dir (.getAbsolutePath code-dir)
                    :master-conf-dir (.getAbsolutePath conf-dir)}))]

      (tk-bootstrap/with-app-with-config
       app
       jruby-testutils/jruby-service-and-dependencies
       config
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
             instance (jruby-testutils/borrow-instance jruby-service :test)
             jruby-puppet (:jruby-puppet instance)
             env-registry (:environment-registry instance)

             _ (testutils/create-file (fs/file conf-dir "puppet.conf")
                                      "[main]\nenvironment_timeout=unlimited\nbasemodulepath=$codedir/modules\n")

             env-dir (fn [env-name]
                       (fs/file code-dir "environments" env-name))
             env-1-dir (env-dir "env1")
             env-1-dir-and-manifests [env-1-dir ["foo" "bar"]]
             _ (create-env env-1-dir-and-manifests)

             get-module-info-for-env (fn [env]
                                       (-> (.getModuleInfoForEnvironment jruby-puppet
                                                                         env)
                                           (roundtrip-via-json)))]
         (try
           (testing "retrieves basic module information about installed modules"
             (let [env-1-module-info #{{"name" "foo", "version" "1.0.0"}
                                       {"name" "bar", "version" "1.0.0"}}]
               (is (= env-1-module-info
                      (set (get-module-info-for-env "env1")))
                   "Unexpected info retrieved for 'env1'")))

           (testing "returns nothing if a fake env is given"
             (is (= nil (get-module-info-for-env "bogus_env"))))
           (finally
             (jruby-testutils/return-instance jruby-service instance :test))))))))
