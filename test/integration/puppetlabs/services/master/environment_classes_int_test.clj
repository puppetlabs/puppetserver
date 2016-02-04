(ns puppetlabs.services.master.environment-classes-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as cheshire]
            [me.raynes.fs :as fs]
            [ring.util.response :as ring]))

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
         response->class-info-map #(-> %1 :body cheshire/parse-string)
         foo-file (testutils/write-foo-pp-file
                   "class foo (String $foo_1 = \"is foo\"){}")
         bar-file (testutils/write-foo-pp-file
                   "class foo::bar (Integer $foo_2 = 3){}" "bar")
         expected-initial-response {
                                    "files"
                                    [
                                     {"path" bar-file
                                      "classes"
                                      [
                                       {
                                        "name" "foo::bar"
                                        "params"
                                        [
                                         {"name" "foo_2",
                                          "type" "Integer",
                                          "default_literal" 3,
                                          "default_source" "3"}]}]}
                                     {"path" foo-file,
                                      "classes"
                                      [
                                       {
                                        "name" "foo"
                                        "params"
                                        [
                                         {"name" "foo_1",
                                          "type" "String",
                                          "default_literal" "is foo",
                                          "default_source" "\"is foo\""}]}]}]
                                    "name" "production"}
         initial-response (get-production-env-classes)
         response-e-tag #(ring/get-header % "Etag")
         initial-e-tag (response-e-tag initial-response)]
     (testing "initial fetch of environment_classes info is good"
       (is (= 200 (:status initial-response))
           "unexpected status code for initial response")
       (is (not (nil? initial-e-tag))
           "no e-tag found for initial response")
       (is (= expected-initial-response
              (response->class-info-map initial-response))
           "unexpected body for initial response"))
     (testing "e-tag not updated for second fetch when code has not changed"
       (let [response (get-production-env-classes)]
         (is (= 200 (:status response))
             "unexpected status code for response following no code change")
         (is (= initial-e-tag (response-e-tag response))
             "e-tag changed even though code did not")
         (is (= expected-initial-response
                (response->class-info-map initial-response))
             "unexpected body for initial response")))
     (testing (str "environment_classes fetch includes latest info after "
                   "code update")
       (testutils/write-foo-pp-file
        (str "class foo (Hash[String, Integer] $foo_hash = {"
             " foo_1 => 1, foo_2 => 2 }){}"))
       (testutils/write-foo-pp-file "" "bar")
       (let [baz-file (testutils/write-foo-pp-file
                       (str "class foo::baz (String $baz_1 = 'the baz',\n"
                            " Array[String] $baz_arr = ['one', 'two', "
                            "'three']){}\n"
                            "class foo::morebaz ($baz) {}")
                       "baz")
             borked-file (testutils/write-foo-pp-file
                          (str "borked manifest") "borked")
             response (get-production-env-classes)]
         (is (= 200 (:status response))
             "unexpected status code for response following code change")
         (is (not= initial-e-tag (response-e-tag response))
             "e-tag did not change even though code did")
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
                          {"path" borked-file
                           "error" (str "This Name has no effect. A value "
                                        "was produced and then forgotten ("
                                        "one or more preceding expressions "
                                        "may have the wrong form) at "
                                        borked-file
                                        ":1:1")}
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
                (response->class-info-map response))
             "unexpected body following code change"))))))
