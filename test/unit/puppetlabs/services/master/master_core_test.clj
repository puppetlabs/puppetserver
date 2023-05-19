(ns puppetlabs.services.master.master-core-test
  (:require [cheshire.core :as json]
            [clojure.string :refer [split]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core
             :as
             jruby-pool-manager-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.master.master-core :refer [root-routes
                                                            wrap-middleware
                                                            if-none-match-from-request
                                                            class-info-from-jruby->class-info-for-json
                                                            valid-static-file-path?
                                                            validate-memory-requirements!
                                                            meminfo-content
                                                            max-heap-size
                                                            task-file-uri-components
                                                            all-tasks-response!]]
            [puppetlabs.services.master.master-service :as master-service]
            [puppetlabs.services.protocols.jruby-puppet :as jruby]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [ring.middleware.params :as ring]
            [ring.mock.request :as ring-mock]
            [schema.test :as schema-test])
  (:import java.util.HashMap))

(use-fixtures :once schema-test/validate-schemas)

(def dummy-jruby-service
  (reify jruby/JRubyPuppetService))

(defn construct-info-from-task-name
  "Given a task name in format 'module::task', construct a data structure that
  matches what the all-tasks function on the JRuby service would return. Also
  accepts name of 'module', for the special-cased init tasks."
  [task-name]
  (let [[module _] (split task-name #"::")]
    {:module {:name module}
     :name task-name
     :metadata {:private false :description "test description"}}))

(defn build-ring-handler
  [request-handler puppet-version jruby-service]
  (-> (root-routes request-handler ring/wrap-params
                   jruby-service
                   identity
                   (fn [___] (throw (IllegalStateException. "Versioned code not supported.")))
                   (constantly nil)
                   true
                   nil
                   ["./dev-resources/puppetlabs/services/master/master_core_test/builtin_bolt_content"]
                   "./dev-resources/puppetlabs/services/master/master_core_test/bolt_projects")
      (comidi/routes->handler)
      (wrap-middleware identity puppet-version)))

(defn app-request
  ([app path] (app-request app :get path))
  ([app method path] (app (ring-mock/request method path))))

(deftest test-master-routes
  (let [handler     (fn ([req] {:request req}))
        app         (build-ring-handler handler "1.2.3" dummy-jruby-service)
        request     (partial app-request app)]
    (is (= 200 (:status (request "/v3/environments"))))
    (is (= 200 (:status (request "/v3/catalog/bar?environment=environment1234"))))
    (is (= 200 (:status (app (-> {:request-method :post
                                  :uri "/v3/catalog/bar"
                                  :content-type "application/x-www-form-urlencoded"}
                                 (ring-mock/body "environment=environment1234"))))))
    (is (nil? (request "/foo")))
    (is (nil? (request "/foo/bar")))
    (doseq [[method paths]
            {:get ["node"
                   "file_metadata"
                   "file_bucket_file"]
             :put ["file_bucket_file"
                   "report"]
             :head ["file_bucket_file"]}
            path paths]
      (let [resp (request method (str "/v3/" path "/bar"))]
        (is (= 200 (:status resp))
            (str "Did not get 200 for method: "
                 method
                 ", path: "
                 path))))))

(deftest if-none-match-test
  (testing "if-none-match returns expected value when"
    (let [mock-request (ring-mock/request :get "env_classes")]
      (testing "header present without '--gzip' suffix"
        (is (= "abc123"
               (if-none-match-from-request
                (ring-mock/header mock-request "If-None-Match" "abc123")))))
      (testing "header present with '--gzip' suffix (SERVER-1153)"
        (is (= "abc123"
               (if-none-match-from-request
                (ring-mock/header mock-request
                                  "If-None-Match"
                                  "abc123--gzip")))))
      (testing "header present with empty value"
        (is (empty? (if-none-match-from-request
                     (ring-mock/header mock-request "If-None-Match" "")))))
      (testing "header absent"
        (is (nil? (if-none-match-from-request mock-request)))))))

(deftest environment-classes-test
  (testing "environment_classes query"
    (with-redefs [jruby-core/borrow-from-pool-with-timeout (fn [_ _ _] {:jruby-puppet (Object.)})
                  jruby-core/return-to-pool (fn [_ _ _ _] #())]
      (let [jruby-service (reify jruby/JRubyPuppetService
                            (get-pool-context [_] (jruby-pool-manager-core/create-pool-context
                                                   (jruby-core/initialize-config {:gem-home "bar"
                                                                                  :gem-path "bar:foobar"
                                                                                  :ruby-load-path ["foo"]})))
                            (get-environment-class-info [_ _ env]
                              (when (= env "production")
                                {}))
                            (get-cached-content-version
                              [_ _ _])
                            (set-cache-info-tag! [_ _ _ _ _]))
            handler (fn ([req] {:request req}))
            app (build-ring-handler handler "1.2.3" jruby-service)
            request (partial app-request app)
            etag #(-> %
                      (class-info-from-jruby->class-info-for-json "production")
                      json/encode
                      ks/utf8-string->sha256)
            map-with-classes #(doto (HashMap.)
                               (.put "classes" %))]
        (testing "returns 200 for environment that exists"
          (is (= 200 (:status (request
                               "/v3/environment_classes?environment=production")))))
        (testing "returns 404 not found when non-existent environment supplied"
          (is (= 404 (:status (request
                               "/v3/environment_classes?environment=test")))))
        (testing "returns 400 bad request when environment not supplied"
          (logging/with-test-logging
           (is (= 400 (:status (request "/v3/environment_classes"))))))
        (testing (str "returns 400 bad request when environment has "
                      "non-alphanumeric characters")
          (logging/with-test-logging
           (is (= 400 (:status (request
                                "/v3/environment_classes?environment=~"))))))
        (testing "calculates etag properly for response payload"
          (is (= (etag {"/one/file"
                        (map-with-classes
                         [
                          {"name" "oneclass",
                           "params" [
                                     {"name" "oneparam",
                                      "type" "String",
                                      "default_literal" "'literal'",
                                      "default_source" "literal"},
                                     {"name" "twoparam",
                                      "type" "Integer",
                                      "default_literal" "3",
                                      "default_source" "3"}]
                           },
                          {"name" "twoclass"
                           "params" []}]),
                        "/two/file" (map-with-classes [])})
                 (etag {"/one/file"
                        (map-with-classes
                         [
                          {"name" "oneclass",
                           "params" [
                                     {"default_source" "literal"
                                      "type" "String",
                                      "name" "oneparam",
                                      "default_literal" "'literal'"},
                                     {"name" "twoparam",
                                      "type" "Integer",
                                      "default_literal" "3",
                                      "default_source" "3"}]
                           },
                          {"name" "twoclass"
                           "params" []}]),
                        "/two/file" (map-with-classes [])}))
              "hashes unexpectedly not equal for equal maps")
          (is (= (etag {"/one/file"
                        (map-with-classes
                         [
                          {"name" "oneclass",
                           "params" [
                                     {"name" "oneparam",
                                      "type" "String",
                                      "default_literal" "'literal'",
                                      "default_source" "literal"},
                                     {"name" "twoparam",
                                      "type" "Integer",
                                      "default_literal" "3",
                                      "default_source" "3"}]
                           },
                          {"name" "twoclass"
                           "params" []}]),
                        "/two/file" (map-with-classes [])})
                 (etag {"/one/file"
                        (map-with-classes
                         [
                          {"name" "oneclass",
                           "params" [
                                     {"default_source" "literal"
                                      "type" "String",
                                      "name" "oneparam",
                                      "default_literal" "'literal'"},
                                     {"type" "Integer",
                                      "name" "twoparam",
                                      "default_literal" "3"
                                      "default_source" "3"}]
                           },
                          {"params" []
                           "name" "twoclass"}]),
                        "/two/file" (map-with-classes [])}))
              (str "hashes unexpectedly not equal for equal maps with out of "
                   "order keys"))
          (is (not= (etag {"/one/file"
                           (map-with-classes
                            [
                             {"name" "oneclass",
                              "params" [
                                        {"name" "oneparam",
                                         "type" "String",
                                         "default_literal" "'literal'",
                                         "default_source" "literal"},
                                        {"name" "twoparam",
                                         "type" "Integer",
                                         "default_literal" "3",
                                         "default_source" "3"}]
                              },
                             {"name" "twoclass"
                              "params" []}]),
                           "/two/file" (map-with-classes [])})
                    (etag {"/two/file" (map-with-classes [])}))
              "hashes unexpectedly equal for different payloads"))
        (testing (str "throws IllegalArgumentException for response "
                      "which contains invalid map key for etagging")
          (is (thrown-with-msg?
               IllegalArgumentException
               #"Object cannot be coerced to a keyword"
               (etag {"/one/file"
                      (map-with-classes
                       [{["array"
                          "as"
                          "map"
                          "key"
                          "not"
                          "supported"]
                         "bogus"}])}))))))))

(deftest valid-static-file-path-test
  (let [valid-paths ["modules/foo/files/bar.txt"
                     "modules/foo/files/bar"
                     "modules/foo/files/bar/baz.txt"
                     "modules/foo/files/bar/more/path/elements/baz.txt"
                     "modules/foo/files/bar/%2E%2E/baz.txt"
                     "modules/foo/scripts/qux.sh"
                     "modules/foo/tasks/task.sh"
                     "environments/production/files/~/.bash_profile"
                     "dist/foo/files/bar.txt"
                     "site/foo/files/bar.txt"]
        invalid-paths ["modules/foo/manifests/bar.pp"
                       "modules/foo/files/"
                       "manifests/site.pp"
                       "environments/foo/bar/files"
                       "environments/../manifests/files/site.pp"
                       "environments/files/site.pp"
                       "environments/../modules/foo/lib/puppet/parser/functions/site.rb"
                       "environments/../modules/foo/files/site.pp"
                       "environments/production/modules/foo/files/../../../../../../site.pp"
                       "environments/production/modules/foo/files/bar.txt"
                       "environments/production/modules/foo/files/..conf..d../..bar..txt.."
                       "environments/test/modules/foo/files/bar/baz.txt"
                       "environments/dev/modules/foo/files/path/to/file/something.txt"]
        check-valid-path (fn [path] {:path path :valid? (valid-static-file-path? path)})
        valid-path-results (map check-valid-path valid-paths)
        invalid-path-results (map check-valid-path invalid-paths)
        get-validity (fn [{:keys [valid?]}] valid?)]
    (testing "Only files in 'modules/*/(files | scripts | tasks)/**' are valid"
      (is (every? get-validity valid-path-results) (ks/pprint-to-string valid-path-results))
      (is (every? (complement get-validity) invalid-path-results) (ks/pprint-to-string invalid-path-results)))))

(deftest file-bucket-file-content-type-test
  (testing "The 'Content-Type' header on incoming /file_bucket_file requests is not overwritten, and simply passed through unmodified."
    (let [handler     (fn ([req] {:request req}))
          app         (build-ring-handler handler "1.2.3" dummy-jruby-service)
          resp        (app (-> {:request-method :put
                                :content-type "application/octet-stream"
                                :uri "/v3/file_bucket_file/bar"}
                               (ring-mock/body "foo")))]
      (is (= "application/octet-stream"
             (get-in resp [:request :content-type])))

      (testing "Even if the client sends something insane, just pass it through and let the puppet code handle it."
        (let [resp (app (-> {:request-method :put
                             :content-type "something-crazy/for-content-type"
                             :uri "/v3/file_bucket_file/bar"}
                            (ring-mock/body "foo")))]
          (is (= "something-crazy/for-content-type"
                 (get-in resp [:request :content-type]))))))))

(deftest code-id-injection-test
  (testing "code_id is not added to non-catalog requests"
    (let [handler (fn ([req] {:request req}))
          app (build-ring-handler handler "1.2.3" dummy-jruby-service)
          request (partial app-request app)]
      (doseq [[method paths]
              {:get ["node"
                     "file_content"
                     "file_metadatas"
                     "file_metadata"
                     "file_bucket_file"
                     "status"]
               :put ["file_bucket_file"
                     "report"]
               :head ["file_bucket_file"]}
              path paths]
        (let [resp (request method (str "/v3/" path "/bar?environment=environment1234"))]
          (is (nil? (get-in resp [:request :params "code_id"])))
          (is (nil? (get-in resp [:request :include-code-id?]))))))))

(defn assert-failure-msg
  "Assert the message thrown by validate-memory-requirements! matches re"
  [re behavior-msg]
  (testing (str "the error " behavior-msg)
    (is (thrown-with-msg? Error re (validate-memory-requirements!)))))

(deftest validate-memory-requirements!-test
  (testing "when /proc/meminfo does not exist"
    (with-redefs [meminfo-content (constantly nil)
                  max-heap-size 2097152]
      (is (nil? (validate-memory-requirements!))
          "nil when /proc/meminfo does not exist")))
  (testing "when ram is > 1.1 times JVM max heap"
    (with-redefs [meminfo-content #(str "MemTotal:        3878212 kB\n")
                  max-heap-size 2097152]
      (is (nil? (validate-memory-requirements!))
          "nil when ram is > 1.1 times JVM max heap")))
  (testing "when ram is < 1.1 times JVM max heap"
    (with-redefs [meminfo-content #(str "MemTotal:        1878212 kB\n")
                  max-heap-size 2097152]
      (assert-failure-msg #"RAM (.*) JVM heap"
                          "mentions RAM and JVM Heap size")
      (assert-failure-msg #"JAVA_ARGS"
                          "suggests the user configure JAVA_ARGS")
      (assert-failure-msg #"computed as 1.1 *"
                          "informs the user how required memory is calculated")
      (assert-failure-msg #"/etc/sysconfig/puppetserver"
                          "points the user to the EL config location")
      (assert-failure-msg #"/etc/default/puppetserver"
                          "points the user to the debian config location"))))

(deftest task-file-uri-components-test
  (is (= ["modules" "mymodule" "tasks" "foo.sh"] (task-file-uri-components "foo.sh" "/path/to/environment/modules/mymodule/tasks/foo.sh")))
  (is (= ["modules" "mymodule" "scripts" "foo.sh"] (task-file-uri-components "mymodule/scripts/foo.sh" "/path/to/environment/modules/mymodule/scripts/foo.sh")))
  (testing "works with nested files"
      (is (= ["modules" "mymodule" "lib" "baz/bar/foo.sh"]
             (task-file-uri-components "mymodule/lib/baz/bar/foo.sh" "/path/to/environment/modules/mymodule/lib/baz/bar/foo.sh"))))
  (testing "when the module name is the same as a module subdirectory"
    (doseq [module ["tasks" "scripts" "files" "lib"]
            subdir ["tasks" "scripts" "files" "lib"]
            :let [file-name (if (= subdir "tasks") "foo.sh" (format "%s/%s/foo.sh" module subdir))]]
      (is (= ["modules" module subdir "foo.sh"]
             (task-file-uri-components file-name (format "/path/to/environment/modules/%s/%s/foo.sh" module subdir)))))))

(deftest all-tasks-response-test
  (testing "all-tasks query"
    (with-redefs [jruby-core/borrow-from-pool-with-timeout (fn [_ _ _] {:jruby-puppet (Object.)})
                  jruby-core/return-to-pool (fn [_ _ _ _] #())]
      (let [jruby-service (reify jruby/JRubyPuppetService
                            (get-pool-context [_] (jruby-pool-manager-core/create-pool-context
                                                   (jruby-core/initialize-config {:gem-home "bar"
                                                                                  :gem-path "bar:foobar"
                                                                                  :ruby-load-path ["foo"]})))
                            (get-tasks [_ _ env]
                              (when (= env "production")
                                [])))
            handler (fn ([req] {:request req}))
            app (build-ring-handler handler "1.2.3" jruby-service)
            request (partial app-request app)
            response (fn [info]
                      (all-tasks-response!
                        info
                        "production"))
            response-format (fn [task-name]
                                 {:name task-name
                                  :environment [{:name "production"
                                                 :code_id nil}]
                                  :private false
                                  :description "test description"})
            expected-response (fn [task-names]
                                (map response-format task-names))
            task-names ["apache" "apache::configure" "mongodb::uninstall"]]
        (testing "returns 200 for environment that exists"
          (is (= 200 (:status (request
                               "/v3/tasks?environment=production")))))
        (testing "returns 404 not found when non-existent environment supplied"
          (is (= 404 (:status (request
                               "/v3/tasks?environment=test")))))
        (testing "returns 400 bad request when environment not supplied"
          (logging/with-test-logging
           (is (= 400 (:status (request "/v3/tasks"))))))

        (testing (str "returns 400 bad request when environment has "
                      "non-alphanumeric characters")
          (logging/with-test-logging
           (is (= 400 (:status (request
                                "/v3/tasks?environment=~"))))))

        (testing "formats response body properly"
          (is (= (set (expected-response task-names))
                 (-> (map construct-info-from-task-name task-names)
                     response
                     :body
                     (json/decode true)
                     set))))))))

(deftest compile-endpoint
  (with-redefs [jruby-core/borrow-from-pool-with-timeout (fn [_ _ _] {:jruby-puppet (Object.)})
                jruby-core/return-to-pool (fn [_ _ _ _] #())]
    (let [jruby-service (reify jruby/JRubyPuppetService
                          (get-pool-context [_] (jruby-pool-manager-core/create-pool-context
                                                 (jruby-core/initialize-config {:gem-home "bar"
                                                                                :gem-path "bar:foobar"
                                                                                :ruby-load-path ["foo"]})))
                          (compile-catalog [_ _ _] {:cool "catalog"})
                          (compile-ast [_ _ _ _] {:cool "catalog"}))
          handler (fn ([req] {:request req}))
          app (build-ring-handler handler "1.2.3" jruby-service)]
      (testing "compile endpoint for environments"
        (let [response (app (-> {:request-method :post
                                 :uri "/v3/compile"
                                 :content-type "application/json"}
                                (ring-mock/body (json/encode {:certname "foo"
                                                              :environment "production"
                                                              :code_ast "{\"__pcore_something\": \"Foo\"}"
                                                              :facts {:values {}}
                                                              :trusted_facts {:values {}}
                                                              :variables {:values {}}}))))]
          (is (= 200 (:status response)))))
      (testing "compile endpoint for projects"
        (let [response (app (-> {:request-method :post
                                 :uri "/v3/compile"
                                 :content-type "application/json"}
                                (ring-mock/body (json/encode {:certname "foo"
                                                              :versioned_project "fake_project"
                                                              :code_ast "{\"__pcore_something\": \"Foo\"}"
                                                              :facts {:values {}}
                                                              :trusted_facts {:values {}}
                                                              :variables {:values {}}
                                                              :options {:compile_for_plan true}}))))]
          (is (= 200 (:status response)))))
      (testing "compile endpoint fails with no environment or versioned_project"
        (let [response (app (-> {:request-method :post
                                 :uri "/v3/compile"
                                 :content-type "application/json"}
                                (ring-mock/body (json/encode {:certname "foo"
                                                              :code_ast "{\"__pcore_something\": \"Foo\"}"
                                                              :facts {:values {}}
                                                              :trusted_facts {:values {}}
                                                              :variables {:values {}}
                                                              :options {:compile_for_plan true}}))))]
          (is (= 400 (:status response)))))
      (testing "compile endpoint fails with both environment and versioned_project"
        (let [response (app (-> {:request-method :post
                                 :uri "/v3/compile"
                                 :content-type "application/json"}
                                (ring-mock/body (json/encode {:certname "foo"
                                                              :environment "production"
                                                              :versioned_project "fake_project"
                                                              :code_ast "{\"__pcore_something\": \"Foo\"}"
                                                              :facts {:values {}}
                                                              :trusted_facts {:values {}}
                                                              :variables {:values {}}
                                                              :options {:compile_for_plan true}}))))]
          (is (= 400 (:status response))))))))

(deftest jdk-support-status-test
  (is (= :unsupported (master-service/jdk-support-status "1.7")))
  (is (= :unsupported (master-service/jdk-support-status "1.7.0")))
  (is (= :deprecated (master-service/jdk-support-status "1.8")))
  (is (= :deprecated (master-service/jdk-support-status "1.8.0")))
  (is (= :deprecated (master-service/jdk-support-status "1.9")))
  (is (= :deprecated (master-service/jdk-support-status "1.9.0")))
  (is (= :deprecated (master-service/jdk-support-status "10")))
  (is (= :deprecated (master-service/jdk-support-status "10.0")))
  (is (= :official (master-service/jdk-support-status "11.0")))
  (is (= :official (master-service/jdk-support-status "11.0.7")))
  (is (= :official (master-service/jdk-support-status "17.0")))
  (is (= :official (master-service/jdk-support-status "17.0.4"))))

(deftest v4-routes-test
  (with-redefs [jruby-core/borrow-from-pool-with-timeout (fn [_ _ _] {:jruby-puppet (Object.)})
                jruby-core/return-to-pool (fn [_ _ _ _] #())]
    (let [jruby-service (reify jruby/JRubyPuppetService
                          (get-pool-context [_] (jruby-pool-manager-core/create-pool-context
                                                 (jruby-core/initialize-config {:gem-home "bar"
                                                                                :gem-path "bar:foobar"
                                                                                :ruby-load-path ["foo"]})))
                          (compile-catalog [_ _ _] {:cool "catalog"})
                          (compile-ast [_ _ _ _] {:cool "catalog"}))
          handler (fn ([req] {:request req}))
          app (build-ring-handler handler "1.2.3" jruby-service)]
      (testing "catalog endpoint succeeds"
          (let [response (app (-> {:request-method :post
                                   :uri "/v4/catalog"
                                   :content-type "application/json"}
                                  (ring-mock/body (json/encode {:certname "foo"
                                                                :environment "production"
                                                                :persistence
                                                                {:catalog true
                                                                 :facts true}}))))]
            (is (= 200 (:status response)))
            (is (= {:cool "catalog"} (json/decode (:body response) true)))))
      (testing "catalog endpoint fails with invalid environment name"
          (let [response (app (-> {:request-method :post
                                   :uri "/v4/catalog"
                                   :content-type "application/json"}
                                  (ring-mock/body (json/encode {:certname "foo"
                                                                :environment ""
                                                                :persistence
                                                                {:catalog true
                                                                 :facts true}}))))]
            (is (= 400 (:status response)))
            (is (re-matches #".*Invalid input:.*\"\".*" (:body response))))))))
