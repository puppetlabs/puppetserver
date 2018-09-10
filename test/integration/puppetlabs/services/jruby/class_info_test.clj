(ns puppetlabs.services.jruby.class-info-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]))

(defn gen-module-classes
  [[mod-dir manifests]]
  (let [manifest-dir (fs/file mod-dir "manifests")
        mod-name (fs/base-name mod-dir)]
    (ks/mkdirs! manifest-dir)
    (doseq [manifest manifests]
      (spit (fs/file manifest-dir (str manifest ".pp"))
            (str
              "class " mod-name "::" manifest "($" manifest "_a, Integer $"
              manifest "_b, String $" manifest
              "_c = 'c default value') { }\n"
              "class " mod-name "::" manifest "2($" manifest "2_a, Integer $"
              manifest "2_b, String $" manifest
              "2_c = 'c default value') { }\n")))))

(defn gen-classes
  [[env-dir manifests]]
  (let [manifest-dir (fs/file env-dir "manifests")]
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

(defn create-env
  [[env-dir manifests]]
  (testutils/create-env-conf env-dir "")
  (gen-classes [env-dir manifests]))

(defn roundtrip-via-json
  [obj]
  (-> obj
      (cheshire/generate-string)
      (cheshire/parse-string)))

(defn expected-class-info
  ([class-name]
    {"name" class-name
     "params" [{"name" (str class-name "_a")}
               {"name" (str class-name "_b"),
                "type" "Integer"}
               {"name" (str class-name "_c"),
                "type" "String",
                "default_literal" "c default value"
                "default_source" "'c default value'"}]})
  ([mod-name class-name]
    {"name" (str mod-name "::" class-name)
     "params" [{"name" (str class-name "_a")}
               {"name" (str class-name "_b"),
                "type" "Integer"}
               {"name" (str class-name "_c"),
                "type" "String",
                "default_literal" "c default value"
                "default_source" "'c default value'"}]}))

(defn expected-manifests-info
  [manifests]
  (into {}
        (apply concat
               (for [[dir names] manifests]
                 (let [mod-name (fs/base-name dir)]
                   (for [class-name names]
                     [(.getAbsolutePath
                        (fs/file dir
                                 "manifests"
                                 (str class-name ".pp")))
                      {"classes"
                       (if (str/starts-with? mod-name "mod")
                          [(expected-class-info mod-name class-name)
                           (expected-class-info mod-name (str class-name "2"))]
                          [(expected-class-info class-name)
                           (expected-class-info (str class-name "2"))])}]))))))

(deftest ^:integration class-info-test
  (testing "class info properly enumerated for"
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

             env-2-dir (env-dir "env2")
             env-2-dir-and-manifests [env-2-dir ["baz" "bim" "boom"]]
             _ (create-env env-2-dir-and-manifests)

             env-1-mod-dir (fs/file env-1-dir "modules")
             env-1-mod-1-dir-and-manifests [(fs/file env-1-mod-dir
                                                     "mod1env1")
                                            ["baz" "bim"]]
             _ (gen-module-classes env-1-mod-1-dir-and-manifests)
             env-1-mod-2-dir (fs/file env-1-mod-dir "mod2env1")
             env-1-mod-2-dir-and-manifests [env-1-mod-2-dir
                                            ["baz" "bim"]]
             _ (gen-module-classes env-1-mod-2-dir-and-manifests)

             env-3-dir-and-manifests [(env-dir "env3") ["dip" "dap" "dup"]]

             base-mod-dir (fs/file code-dir "modules")
             base-mod-1-and-manifests [(fs/file base-mod-dir "mod1envbase")
                                       ["bap"]]
             _ (gen-module-classes base-mod-1-and-manifests)

             bogus-env-dir (env-dir "bogus-env")
             _ (create-env [bogus-env-dir []])
             _ (gen-classes [bogus-env-dir ["envbogus"]])
             _ (gen-module-classes [(fs/file base-mod-dir "base-bogus") ["base-bogus1"]])

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
             (testutils/create-env-conf env-1-dir (str "manifest="
                                                       (.getAbsolutePath (fs/file env-1-dir
                                                                                  "manifests"
                                                                                  "foo.pp"))
                                                       "\nmodulepath="
                                                       (.getAbsolutePath (fs/file
                                                                          env-2-dir
                                                                          "modules"))
                                                       "\n"))
             (testutils/create-env-conf env-2-dir (str "modulepath="
                                                       (.getAbsolutePath env-1-mod-dir)
                                                       "\n"))
             (let [foo-manifest (.getAbsolutePath (fs/file env-1-dir
                                                           "manifests"
                                                           "foo.pp"))
                   expected-envs-info {"env1" {foo-manifest
                                               {"classes"
                                                [(expected-class-info "foo")
                                                 (expected-class-info "foo2")]}}
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

           (testing "changes to manifest content"
             (fs/delete-dir env-1-mod-2-dir)
             (let [foo-manifest (.getAbsolutePath (fs/file env-1-dir
                                                           "manifests"
                                                           "foo.pp"))
                   _ (testutils/create-file foo-manifest "class foo () {} \n")
                   expected-envs-info {"env1" {foo-manifest
                                               {"classes"
                                                [{"name" "foo"
                                                  "params" []}]}}
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

           (testing "non existent manifest dir for environment"
             (create-env [(env-dir "env4") nil])
             (let [expected-envs-info {"env4" (expected-manifests-info
                                               [base-mod-1-and-manifests])}]
               (is (= (expected-envs-info "env4")
                      (get-class-info-for-env "env4"))
                   "Unexpected info retrieved for 'env4'")))

           (testing "non-existent environment"
             (is (nil? (get-class-info-for-env "bogus-env"))))
           (testing "(PUP-5744) non-JSON safe default_literals omitted"
             (let [env-5-dir (env-dir "env5")
                   _ (testutils/create-env-conf env-5-dir "modulepath=\n")
                   foo-manifest (.getAbsolutePath (fs/file env-5-dir
                                                           "manifests"
                                                           "foo.pp"))]
               (testutils/create-file foo-manifest
                                      (str
                                       "class foo (\n"
                                       "  Regexp $some_regex = /^.*/,\n"
                                       "  Default $some_default = default,\n"
                                       "  Hash $some_hash = { 1 => 2, "
                                       "\"two\" => 3},\n"
                                       "  Hash $some_nested_hash = { \"one\" => 2, "
                                       "\"two\" => { 3 => 4 }},\n"
                                       "  Hash $another_nested_hash = { \"one\" => 2, "
                                       "\"two\" => { \"three\" => 4 }},\n"
                                       "  Array $some_array = [ 1, /^*$/ ],\n"
                                       "  Array $some_nested_array = [ 1, [ 2, "
                                       "default ] ],\n"
                                       "  Array $another_nested_array = [ 1, [ 2, 3 ] ]\n"
                                       "){}"))
               (is (= {foo-manifest
                       {"classes"
                        [{"name" "foo",
                          "params" [{"default_source" "/^.*/"
                                     "name" "some_regex"
                                     "type" "Regexp"}
                                    {"default_source" "default"
                                     "name" "some_default"
                                     "type" "Default"}
                                    {"default_source" "{ 1 => 2, \"two\" => 3}"
                                     "name" "some_hash"
                                     "type" "Hash"}
                                    {"default_source" (str
                                                       "{ \"one\" => 2, "
                                                       "\"two\" => { 3 => 4 }}")
                                     "name" "some_nested_hash"
                                     "type" "Hash"}
                                    {"default_literal" {"one" 2
                                                        "two" {"three" 4}}
                                     "default_source" (str
                                                       "{ \"one\" => 2, "
                                                       "\"two\" => { \"three\""
                                                       " => 4 }}")
                                     "name" "another_nested_hash"
                                     "type" "Hash"}
                                    {"default_source" "[ 1, /^*$/ ]"
                                     "name" "some_array"
                                     "type" "Array"}
                                    {"default_source" "[ 1, [ 2, default ] ]"
                                     "name" "some_nested_array"
                                     "type" "Array"}
                                    {"default_source" "[ 1, [ 2, 3 ] ]"
                                     "default_literal" [1 [2 3]]
                                     "name" "another_nested_array"
                                     "type" "Array"}]}]}}
                      (get-class-info-for-env "env5"))
                   "Unexpected info retrieved for 'env5'")))
           (testing (str "(PUP-5713) Default parameter value with expression "
                         "parsed properly")
             (let [env-6-dir (env-dir "env6")
                   _ (testutils/create-env-conf env-6-dir "modulepath=\n")
                   foo-manifest (.getAbsolutePath (fs/file env-6-dir
                                                           "manifests"
                                                           "foo.pp"))]
               (testutils/create-file foo-manifest
                                      (str
                                       "class foo (\n"
                                       "  String $one_literal = 'literal string',\n"
                                       "  String $another_literal = \"literal string\",\n"
                                       "  String $with_exp = \"for os in $::osfamily\")"
                                       "{} \n"))
               (is (= {foo-manifest
                       {"classes"
                        [{"name" "foo",
                          "params" [{"default_literal" "literal string"
                                     "default_source" "'literal string'"
                                     "name" "one_literal"
                                     "type" "String"}
                                    {"default_literal" "literal string"
                                     "default_source" "\"literal string\""
                                     "name" "another_literal"
                                     "type" "String"}
                                    {"default_source" "\"for os in $::osfamily\""
                                     "name" "with_exp"
                                     "type" "String"}]}]}}
                      (get-class-info-for-env "env6"))
                   "Unexpected info retrieved for 'env6'")))
           (finally
             (jruby-testutils/return-instance jruby-service instance :test))))))))
