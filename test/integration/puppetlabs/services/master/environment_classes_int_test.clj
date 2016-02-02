(ns puppetlabs.services.master.environment-classes-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/environment_classes_int_test")

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf"))
              (fn [f]
                (let [env-dir (fs/file testutils/conf-dir
                                       "environments")]
                  (fs/delete-dir env-dir)
                  (try
                    (f)
                    (finally
                      (fs/delete-dir env-dir))))))

(deftest ^:integration environment-classes-integration-test
  (bootstrap/with-puppetserver-running app
   {:jruby-puppet {:max-active-instances 1}}
   (let [get-production-env-classes #(http-client/get
                                      (str "https://localhost:8140/puppet/v3/"
                                           "environment_classes?"
                                           "environment=production")
                                      (merge
                                       testutils/ssl-request-options
                                       {:as :text}))
         response->class-info-map #(-> %1 :body cheshire/parse-string)]
     (testing "initial fetch of environment_classes info is good"
       (let [foo-file (testutils/write-foo-pp-file
                       "class foo (String $foo_1 = \"is foo\"){}")
             bar-file (testutils/write-foo-pp-file
                       "class foo::bar (Integer $foo_2 = 3){}" "bar")
             response (get-production-env-classes)]
         (is (= 200 (:status response)))
         (is (= {"name" "production",
                 "files" [
                          {"path" bar-file
                           "classes" [{
                                       "name" "foo::bar"
                                       "params" [
                                                 {"name" "foo_2",
                                                  "type" "Integer",
                                                  "default_literal" 3,
                                                  "default_source"
                                                  "3"}]}]}
                          {"path" foo-file,
                           "classes" [{
                                       "name" "foo"
                                       "params" [
                                                 {"name" "foo_1",
                                                  "type" "String",
                                                  "default_literal"
                                                  "is foo",
                                                  "default_source"
                                                  "\"is foo\""}]}]}]}
                (response->class-info-map response)))))
     (testing (str "environment_classes fetch includes latest info after "
                   "code update")
       (let [foo-file (testutils/write-foo-pp-file
                       (str "class foo (Hash[String, Integer] $foo_hash = {"
                            " foo_1 => 1, foo_2 => 2 }){}"))
             bar-file (testutils/write-foo-pp-file "" "bar")
             baz-file (testutils/write-foo-pp-file
                       (str "class foo::baz (String $baz_1 = 'the baz',\n"
                            " Array[String] $baz_arr = ['one', 'two', "
                            "'three']){}\n"
                            "class foo::morebaz ($baz) {}")
                       "baz")
             response (get-production-env-classes)]
         (is (= 200 (:status response)))
         (is (= {"name" "production",
                 "files" [
                          {"path" bar-file
                           "classes" []}
                          {"path" baz-file
                           "classes" [{
                                       "name" "foo::baz"
                                       "params" [
                                                 {"name" "baz_1",
                                                  "type" "String",
                                                  "default_literal" "the baz",
                                                  "default_source" "'the baz'"}
                                                 {"name" "baz_arr"
                                                  "type" "Array[String]"
                                                  "default_literal"
                                                  ["one" "two" "three"]
                                                  "default_source"
                                                  "['one', 'two', 'three']"}]}
                                      {
                                       "name" "foo::morebaz"
                                       "params" [
                                                 {"name" "baz"}]}]}
                          {"path" foo-file,
                           "classes" [{
                                       "name" "foo"
                                       "params" [
                                                 {"name" "foo_hash",
                                                  "type"
                                                  "Hash[String, Integer]",
                                                  "default_literal"
                                                  {"foo_1" 1
                                                   "foo_2" 2},
                                                  "default_source"
                                                  (str
                                                   "{ foo_1 => 1, "
                                                   "foo_2 => 2 }")}]}]}]}
                (response->class-info-map response))))))))
