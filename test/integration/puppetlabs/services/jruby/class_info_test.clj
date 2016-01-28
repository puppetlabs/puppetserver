(ns puppetlabs.services.jruby.class-info-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire])
  (:import (com.puppetlabs.puppetserver.pool JRubyPool)))

(defn create-file
  [file content]
  (ks/mkdirs! (fs/parent file))
  (spit file content))

(defn gen-classes
  [[mod-dir manifests]]
  (let [manifest-dir (fs/file mod-dir "manifests")]
    (ks/mkdirs! manifest-dir)
    (doseq [manifest manifests]
      (spit (fs/file manifest-dir (str manifest ".pp"))
            (str
              "class " manifest "($" manifest "_a, Integer $"
              manifest "_b, String $" manifest
              "_c = 'c default value') { }\n"
              "class " manifest "2($" manifest "2_a, Integer $"
              manifest "2_b, String $" manifest
              "2_c = 'c default value') { }\n")))))

(defn create-env-conf
  [env-dir content]
  (create-file (fs/file env-dir "environment.conf")
               (str "environment_timeout = unlimited\n"
                    content)))

(defn create-env
  [[env-dir manifests]]
  (create-env-conf env-dir "")
  (gen-classes [env-dir manifests]))

(defn roundtrip-via-json
  [obj]
  (-> obj
      (cheshire/generate-string)
      (cheshire/parse-string)))

(defn expected-class-info
  [class]
    {"name" class
     "params" [{"name" (str class "_a")}
               {"name" (str class "_b"),
                "type" "Integer"}
               {"name" (str class "_c"),
                "type" "String",
                "default_literal" "c default value"
                "default_source" "'c default value'"}]})

(defn expected-manifests-info
  [manifests]
  (into {}
        (apply concat
               (for [[dir names] manifests]
                 (do
                   (for [name names]
                     [(.getAbsolutePath
                        (fs/file dir
                                 "manifests"
                                 (str name ".pp")))
                      [(expected-class-info name)
                       (expected-class-info
                         (str name "2"))]]))))))

(deftest ^:integration class-info-test
  (testing "class info properly enumerated for"
    (let [pool (JRubyPool. 1)
          code-dir (ks/temp-dir)
          conf-dir (ks/temp-dir)
          config (jruby-testutils/jruby-puppet-config
                   {:master-code-dir (.getAbsolutePath code-dir)
                    :master-conf-dir (.getAbsolutePath conf-dir)})
          instance (jruby-internal/create-pool-instance!
                     pool 0 config #() nil)
          jruby-puppet (:jruby-puppet instance)
          container (:scripting-container instance)
          env-registry (:environment-registry instance)

          _ (create-file (fs/file conf-dir "puppet.conf")
                         "[main]\nenvironment_timeout=unlimited\nbasemodulepath=$codedir/modules\n")

          env-dir (fn [env-name]
                    (fs/file code-dir "environments" env-name))
          env-1-dir (env-dir "env1")
          env-1-dir-and-manifests [env-1-dir ["foo" "bar"]]
          _ (create-env env-1-dir-and-manifests)

          env-2-dir (env-dir "env2")
          env-2-dir-and-manifests [env-2-dir ["baz" "bim" "boom"]]
          _ (create-env env-2-dir-and-manifests)

          env-1-mod-dir (fs/file env-1-dir "modules")
          env-1-mod-1-dir-and-manifests [(fs/file env-1-mod-dir
                                                  "envmod1")
                                         ["envmod1baz" "envmod1bim"]]
          _ (gen-classes env-1-mod-1-dir-and-manifests)
          env-1-mod-2-dir (fs/file env-1-mod-dir "envmod2")
          env-1-mod-2-dir-and-manifests [env-1-mod-2-dir
                                         ["envmod2baz" "envmod2bim"]]
          _ (gen-classes env-1-mod-2-dir-and-manifests)

          env-3-dir-and-manifests [(env-dir "env3") ["dip" "dap" "dup"]]

          base-mod-dir (fs/file code-dir "modules")
          base-mod-1-and-manifests [(fs/file base-mod-dir "basemod1")
                                    ["basemod1bap"]]
          _ (gen-classes base-mod-1-and-manifests)

          bogus-env-dir (env-dir "bogus-env")
          _ (create-env [bogus-env-dir []])
          _ (gen-classes [bogus-env-dir ["envbogus"]])
          _ (gen-classes [(fs/file base-mod-dir "base-bogus") ["base-bogus1"]])

          get-class-info-for-env (fn [env]
                                   (-> (.getClassInfoForEnvironment jruby-puppet
                                                                    env)
                                       (roundtrip-via-json)))]
        (try
          (testing "initial parse"
            (let [expected-envs-info {"env1" (expected-manifests-info
                                               [env-1-dir-and-manifests
                                                env-1-mod-1-dir-and-manifests
                                                env-1-mod-2-dir-and-manifests
                                                base-mod-1-and-manifests])
                                      "env2" (expected-manifests-info
                                               [env-2-dir-and-manifests
                                                base-mod-1-and-manifests])}]
              (is (= (expected-envs-info "env1")
                     (get-class-info-for-env "env1"))
                  "Unexpected info retrieved for 'env1'")
              (is (= (expected-envs-info "env2")
                     (get-class-info-for-env "env2"))
                  "Unexpected info retrieved for 'env2'")))

          (testing "changes to module and manifest paths"
            (create-env-conf env-1-dir (str "manifest="
                                            (.getAbsolutePath (fs/file env-1-dir
                                                                       "manifests"
                                                                       "foo.pp"))
                                            "\nmodulepath="
                                            (.getAbsolutePath (fs/file
                                                                env-2-dir
                                                                "modules"))
                                            "\n"))
            (create-env-conf env-2-dir (str "modulepath="
                                            (.getAbsolutePath env-1-mod-dir)
                                            "\n"))
            (let [foo-manifest (.getAbsolutePath (fs/file env-1-dir
                                                          "manifests"
                                                          "foo.pp"))
                  expected-envs-info {"env1" {foo-manifest
                                              [(expected-class-info "foo")
                                               (expected-class-info "foo2")]}
                                      "env2" (expected-manifests-info
                                               [env-2-dir-and-manifests
                                                env-1-mod-1-dir-and-manifests
                                                env-1-mod-2-dir-and-manifests])}]
              (puppet-env/mark-all-environments-expired! env-registry)
              (testing "one environment by name"
                (is (= (expected-envs-info "env1")
                       (get-class-info-for-env "env1"))
                    "Unexpected info retrieved for 'env1'")
                (is (= (expected-envs-info "env2")
                       (get-class-info-for-env "env2"))
                    "Unexpected info retrieved for 'env2'"))))

          (testing "changes to manifest content for"
            (fs/delete-dir env-1-mod-2-dir)
            (let [foo-manifest (.getAbsolutePath (fs/file env-1-dir
                                                          "manifests"
                                                          "foo.pp"))
                  _ (create-file foo-manifest "class foo () {} \n")
                  expected-envs-info {"env1" {foo-manifest
                                              [{"name" "foo"
                                                "params" []}]}
                                      "env2" (expected-manifests-info
                                               [env-2-dir-and-manifests
                                                env-1-mod-1-dir-and-manifests])}]
              (puppet-env/mark-environment-expired! env-registry "env1")
              (is (= (expected-envs-info "env1")
                     (get-class-info-for-env "env1"))
                  "Unexpected info retrieved for 'env1'")
              (puppet-env/mark-environment-expired! env-registry "env2")
              (is (= (expected-envs-info "env2")
                     (get-class-info-for-env "env2"))
                  "Unexpected info retrieved for 'env2'")))

          (testing "changes to environments"
            (fs/delete-dir env-1-dir)
            (let [_ (create-env env-3-dir-and-manifests)
                  expected-envs-info {"env2" (expected-manifests-info
                                               [env-2-dir-and-manifests])
                                      "env3" (expected-manifests-info
                                               [env-3-dir-and-manifests
                                                base-mod-1-and-manifests])}]
              (puppet-env/mark-all-environments-expired! env-registry)
              (is (nil? (get-class-info-for-env "env1"))
                  "Unexpected info retrieved for 'env1'")
              (is (= (expected-envs-info "env2")
                     (get-class-info-for-env "env2"))
                  "Unexpected info retrieved for 'env2'")
              (is (= (expected-envs-info "env3")
                     (get-class-info-for-env "env3"))
                  "Unexpected info retrieved for 'env3'")))

          (testing "non-existent environment"
            (is (nil? (get-class-info-for-env "bogus-env"))))

        (finally
          (.terminate jruby-puppet)
          (.terminate container))))))
