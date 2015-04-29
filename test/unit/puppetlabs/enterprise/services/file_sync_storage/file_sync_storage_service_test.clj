(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service-test
  (:import (org.eclipse.jgit.api.errors TransportException GitAPIException)
           (org.eclipse.jgit.api Git))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(defn parse-response-body
  [response]
  (json/parse-string (slurp (:body response))))

(deftest push-disabled-test
  (testing "The JGit servlet should not accept pushes unless configured to do so"
    (let [server-repo-subpath "push-disabled-test"
          config (merge helpers/webserver-plaintext-config
                        {:file-sync-storage {:data-dir (helpers/temp-dir-as-string)
                                             :repos {(keyword server-repo-subpath)
                                                     {:working-dir server-repo-subpath}}}})]
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app config
        (let [test-clone-dir (helpers/temp-dir-as-string)
              server-repo-url (str (helpers/repo-base-url) "/" server-repo-subpath)]
          (jgit-utils/clone server-repo-url test-clone-dir)
          (testing "An attempt to push to the repo should fail"
            (is (thrown-with-msg?
                  TransportException
                  #"Git access forbidden"
                  (helpers/push-test-commit! test-clone-dir)))))))))

(deftest file-sync-storage-service-simple-workflow-test
  (let [data-dir (helpers/temp-dir-as-string)
        server-repo-subpath "file-sync-storage-service-simple-workflow"]
    (testing "bootstrap the file sync storage service and validate that a simple
            clone/push/clone to the server works over http"
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword server-repo-subpath) {:working-dir server-repo-subpath}}
          false)
        (let [client-orig-repo-dir (helpers/temp-dir-as-string)
              server-repo-url (str
                                (helpers/repo-base-url)
                                "/"
                                server-repo-subpath)
              repo-test-file "test-file"]
          (jgit-utils/clone server-repo-url client-orig-repo-dir)
          (helpers/push-test-commit! client-orig-repo-dir repo-test-file)
          (let [client-second-repo-dir (helpers/temp-dir-as-string)]
            (jgit-utils/clone  server-repo-url client-second-repo-dir)
            (is (= helpers/file-text
                   (slurp (str client-second-repo-dir "/" repo-test-file)))
                "Unexpected file text found in second repository clone")))))))

(deftest ssl-configuration-test
  (testing "file sync storage service cannot perform git operations over
            plaintext when the server is configured using SSL"
    (let [repo-name "ssl-configuration-test"
          config (helpers/storage-service-config-with-repos
                   (helpers/temp-dir-as-string)
                   {(keyword repo-name) {:working-dir repo-name}}
                   true)] ; 'true' results in config with Jetty listening on over HTTPS only
      ;; Ensure that JGit's global config is initially using plaintext.
      (helpers/configure-JGit-SSL! false)
      ;; Starting the storage service with SSL in the config should
      ;; reconfigure JGit's global state to allow access over SSL.
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app config
        (is (thrown? TransportException
                     (jgit-utils/clone
                       (str (helpers/repo-base-url) "/" repo-name)
                       (helpers/temp-dir-as-string))))))))

(deftest configurable-endpoints-test
  (let [repo-path             "/test-repo-path"
        api-path              "/test-api-path"
        server-repo-subpath   "file-sync-storage-service-simple-workflow"
        config                {:file-sync-storage
                                 {:data-dir (helpers/temp-dir-as-string)
                                  :repos {(keyword server-repo-subpath)
                                          {:working-dir server-repo-subpath}}}
                               :webserver {:port helpers/http-port}
                               :web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                                                     {:api          api-path
                                                      :repo-servlet repo-path}}}]

    (helpers/with-bootstrapped-file-sync-storage-service-for-http app config
      (let [client-orig-repo-dir  (helpers/temp-dir-as-string)
            server-repo-url       (str
                                    (helpers/repo-base-url repo-path false)
                                    "/"
                                    server-repo-subpath)]

        (testing "The URL path at which the service mounts "
                  "the JGit servlet is configurable"
          (testing "Clone and verify the repo"
            (let [local-repo (jgit-utils/clone server-repo-url client-orig-repo-dir)]
              (is (not (nil? local-repo))
                  (format "Repository cloned from server (%s) to (%s) should be non-nil"
                          server-repo-url
                          client-orig-repo-dir)))))

        (testing "The URL path at which the service mounts "
                 "the ring app is configurable"
          (testing "We can make a basic HTTP request to the server"
            (let [response (http-client/get
                             (str
                               helpers/server-base-url
                               api-path
                               common/latest-commits-sub-path))]
              (is (= 200 (:status response))))))))))

(deftest latest-commits-test
  (let [data-dir (helpers/temp-dir-as-string)
        server-repo-subpath-1 "latest-commits-test-1"
        server-repo-subpath-2 "latest-commits-test-2"
        server-repo-subpath-no-commits "latest-commits-test-3"]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/storage-service-config-with-repos
        data-dir
        {(keyword server-repo-subpath-1) {:working-dir server-repo-subpath-1}
         (keyword server-repo-subpath-2) {:working-dir server-repo-subpath-2}
         (keyword server-repo-subpath-no-commits) {:working-dir server-repo-subpath-no-commits}}
        false)

      (let [client-orig-repo-dir-1 (helpers/clone-and-push-test-commit!
                                     server-repo-subpath-1)
            client-orig-repo-dir-2 (helpers/clone-and-push-test-commit!
                                     server-repo-subpath-2)]

        (testing "Validate /latest-commits endpoint"
          (let [response (http-client/get (str
                                            helpers/server-base-url
                                            helpers/default-api-path-prefix
                                            common/latest-commits-sub-path))
                content-type (get-in response [:headers "content-type"])]

            (testing "the endpoint returns JSON"
              (is (.startsWith content-type "application/json")
                  (str
                    "The response's Content-type should be JSON. Reponse: "
                    response)))

            (testing "the SHA-1 IDs it returns are correct"
              (let [body (parse-response-body response)]
                (is (map? body))

                (testing "A repository with no commits in it returns a null ID"
                  (is (contains? body server-repo-subpath-no-commits))
                  (let [rev (get body server-repo-subpath-no-commits)]
                    (is (= rev nil))))

                (testing "the first repo"
                  (let [actual-rev (get body server-repo-subpath-1)
                        expected-rev (jgit-utils/head-rev-id-from-working-tree
                                       client-orig-repo-dir-1)]
                    (is (= actual-rev expected-rev))))

                (testing "The second repo"
                  (let [actual-rev (get body server-repo-subpath-2)
                        expected-rev (jgit-utils/head-rev-id-from-working-tree
                                       client-orig-repo-dir-2)]
                    (is (= actual-rev expected-rev))))))))))))

(defn get-commit
  [repo]
  (try (-> repo
           Git/open
           .log
           .call
           first)
       ;; Want all tests to run even if one test has an error
       (catch GitAPIException e
         "Could not get git commit for repo " repo "Error: " e)))

(def publish-url (str helpers/server-base-url
                      helpers/default-api-path-prefix
                      common/publish-content-sub-path))

(defn make-publish-request
  [body]
  (http-client/post publish-url
             {:body    (json/encode body)
              :headers {"Content-Type" "application/json"}}))

(deftest publish-content-endpoint-success-test
  (testing "publish content endpoint makes correct commit"
    (let [repo "test-commit"
          working-dir (helpers/temp-dir-as-string)
          data-dir (helpers/temp-dir-as-string)
          server-repo (fs/file data-dir repo)]

      (helpers/add-remote! (helpers/init-repo! (fs/file working-dir))
                           "origin"
                           (str (helpers/repo-base-url) "/" repo))

      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword repo) {:working-dir working-dir}}
          false)
        (testing "with no body supplied"
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= core/default-commit-message
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= core/default-commit-author-name (.getName (.getAuthorIdent commit))))
                (is (= core/default-commit-author-email
                       (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with just author supplied"
          (let [author {:name  "Tester"
                        :email "test@example.com"}
                response (make-publish-request {:author author})
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= core/default-commit-message
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= (:name author) (.getName (.getAuthorIdent commit))))
                (is (= (:email author) (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with author and message supplied"
          (let [author {:name  "Tester"
                        :email "test@example.com"}
                message "This is a test commit"
                response (make-publish-request {:author author :message message})
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= message (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= (:name author) (.getName (.getAuthorIdent commit))))
                (is (= (:email author) (.getEmailAddress (.getAuthorIdent commit))))))))))))

(deftest publish-content-endpoint-error-test
  (testing "publish content endpoint returns well-formed errors"
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/storage-service-config-with-repos
        (helpers/temp-dir-as-string)
        {:repo-name {:working-dir (helpers/temp-dir-as-string)}}
        false)

      (testing "when request body does not match schema"
        (let [response (make-publish-request {:author "bad"})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "user-data-invalid" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when request body is malformed json"
        (let [response (http-client/post publish-url
                                  {:body    "malformed"
                                   :headers {"Content-Type" "application/json"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "json-parse-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when request body is not json"
        (let [response (http-client/post publish-url {:body    "not json"
                                               :headers {"Content-Type" "text/plain"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "content-type-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body)))))))

(deftest publish-content-endpoint-response-test
  (testing "publish content endpoint returns correct response"
    (let [failed-repo "publish-failed"
          nonexistent-repo "publish-non-existent"
          success-repo "publish-success"
          working-dir-failed (helpers/temp-dir-as-string)
          working-dir-success (helpers/temp-dir-as-string)
          data-dir (helpers/temp-dir-as-string)]

      (helpers/init-repo! (fs/file working-dir-failed))
      (helpers/add-remote! (helpers/init-repo! (fs/file working-dir-success))
                           "origin"
                           (str (helpers/repo-base-url) "/" success-repo))

      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          data-dir
          {(keyword failed-repo)      {:working-dir working-dir-failed}
           (keyword nonexistent-repo) {:working-dir "not/a/directory"}
           (keyword success-repo)     {:working-dir working-dir-success}}
          false)
        (with-test-logging
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (testing "get a 200 response"
              (is (= (:status response) 200)))

            (let [data (json/parse-string body)]
              (testing "for repo that was successfully published"
                (is (not= nil (get data success-repo)))
                (is (= (jgit-utils/head-rev-id-from-working-tree (fs/file working-dir-success))
                       (get data success-repo))
                    (str "Could not find correct body for " failed-repo " in " body)))
              (testing "for nonexistent repo"
                (is (re-matches #".*repo-not-found-error"
                                (get-in data [nonexistent-repo "error" "type"]))
                    (str "Could not find correct body for " nonexistent-repo " in " body)))
              (testing "for repo that failed to publish"
                (is (re-matches #".*publish-error"
                                (get-in data [failed-repo "error" "type"]))
                    (str "Could not find correct body for " failed-repo " in " body))))))))))
