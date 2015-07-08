(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service-test
  (:import (org.eclipse.jgit.api.errors TransportException)
           (org.eclipse.jgit.api Git))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [schema.test :as schema-test]
            [clj-time.core :as time]
            [clj-time.format :as time-format]))

(use-fixtures :once schema-test/validate-schemas)

(deftest ^:integration push-disabled-test
  (testing "The JGit servlet should not accept pushes"
    (let [repo-id "push-disabled-test"
          config (helpers/storage-service-config
                   (helpers/temp-dir-as-string)
                   {(keyword repo-id) {:working-dir repo-id}})]
      (helpers/with-bootstrapped-storage-service
        app config
        (let [clone-dir (helpers/temp-dir-as-string)
              server-repo-url (str (helpers/repo-base-url) "/" repo-id)]
          (jgit-utils/clone server-repo-url clone-dir)
          (testing "An attempt to push to the repo should fail"
            (is (thrown-with-msg?
                  TransportException
                  #"authentication not supported"
                  (helpers/push-test-commit! clone-dir)))))))))

(deftest ^:integration file-sync-storage-service-simple-workflow-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        data-dir (core/path-to-data-dir root-data-dir)
        repo-id "file-sync-storage-service-simple-workflow"]
    (testing "bootstrap the file sync storage service and validate that a simple
            clone/push/clone to the server works over http"
      (helpers/with-bootstrapped-storage-service
        app
        (helpers/storage-service-config
          root-data-dir
          {(keyword repo-id) {:working-dir repo-id}})
        (let [server-repo-url (str
                                (helpers/repo-base-url)
                                "/"
                                repo-id)
              repo-test-file "test-file"]
          (helpers/clone-and-push-test-commit! repo-id data-dir repo-test-file)
          (let [client-second-repo-dir (helpers/temp-dir-as-string)]
            (jgit-utils/clone  server-repo-url client-second-repo-dir)
            (is (= helpers/file-text
                   (slurp (str client-second-repo-dir "/" repo-test-file)))
                "Unexpected file text found in second repository clone")))))))

(deftest ^:integration ssl-configuration-test
  (testing "file sync storage service cannot perform git operations over
            plaintext when the server is configured using SSL"
    (let [repo-name "ssl-configuration-test"
          config (helpers/storage-service-config
                   (helpers/temp-dir-as-string)
                   {(keyword repo-name) {:working-dir repo-name}}
                   true)] ; 'true' results in config with Jetty listening on over HTTPS only
      ;; Ensure that JGit's global config is initially using plaintext.
      (helpers/configure-JGit-SSL! false)
      ;; Starting the storage service with SSL in the config should
      ;; reconfigure JGit's global state to allow access over SSL.
      (helpers/with-bootstrapped-storage-service
        app config
        (is (thrown? TransportException
                     (jgit-utils/clone
                       (str (helpers/repo-base-url) "/" repo-name)
                       (helpers/temp-dir-as-string))))))))

(def latest-commits-url (str
                          helpers/server-base-url
                          helpers/default-api-path-prefix
                          "/v1"
                          common/latest-commits-sub-path))

(deftest ^:integration latest-commits-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        data-dir (core/path-to-data-dir root-data-dir)
        repo1-id "latest-commits-test-1"
        repo2-id "latest-commits-test-2"
        repo3-id "latest-commits-test-3"]
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        root-data-dir
        {(keyword repo1-id) {:working-dir repo1-id}
         (keyword repo2-id) {:working-dir repo2-id}
         (keyword repo3-id) {:working-dir repo3-id}})

      (let [client-orig-repo-dir-1 (helpers/clone-and-push-test-commit! repo1-id data-dir)
            client-orig-repo-dir-2 (helpers/clone-and-push-test-commit! repo2-id data-dir)]

        (testing "Validate /latest-commits endpoint"
          (let [response (http-client/post latest-commits-url {:as :text})
                content-type (get-in response [:headers "content-type"])]

            (testing "the endpoint returns JSON"
              (is (.startsWith content-type "application/json")
                  (str
                    "The response's Content-type should be JSON. Reponse: "
                    response)))

            (testing "the SHA-1 IDs it returns are correct"
              (let [body (common/parse-latest-commits-response response)]
                (is (map? body))

                (testing "A repository with no commits in it returns a nil ID"
                  (is (contains? body (keyword repo3-id)))
                  (let [commit-data (get body (keyword repo3-id))]
                    (is (= commit-data nil))))

                (testing "the first repo"
                  (let [actual-rev (get-in body [(keyword repo1-id) :commit])
                        expected-rev (jgit-utils/head-rev-id-from-working-tree
                                       client-orig-repo-dir-1)]
                    (is (= actual-rev expected-rev))
                    (is (nil? (get-in body [(keyword repo1-id) :submodules])))))

                (testing "The second repo"
                  (let [actual-rev (get-in body [(keyword repo2-id) :commit])
                        expected-rev (jgit-utils/head-rev-id-from-working-tree
                                       client-orig-repo-dir-2)]
                    (is (= actual-rev expected-rev))
                    (is (nil? (get-in body [(keyword repo2-id) :submodules])))))))))))))

(def publish-url (str helpers/server-base-url
                      helpers/default-api-path-prefix
                      "/v1"
                      common/publish-content-sub-path))

(deftest ^:integration latest-commits-with-submodules-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        data-dir (core/path-to-data-dir root-data-dir)
        repo-id "latest-commits-submodules-test"
        git-dir (common/bare-repo data-dir repo-id)
        working-dir (helpers/temp-dir-as-string)
        submodules-working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodule-id "submodule"]
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        root-data-dir
        {(keyword repo-id) {:working-dir working-dir
                            :submodules-dir submodules-dir
                            :submodules-working-dir submodules-working-dir}})

      ; Initialize the submodule
      (ks/mkdirs! (fs/file submodules-working-dir submodule-id))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-id "test.txt"))

      (testing (str "Submodules will not appear in latest-commits response "
                    "until they are published")
        (let [response (http-client/post latest-commits-url {:as :text})
              body (common/parse-latest-commits-response response)]
          (is (contains? body (keyword repo-id)))
          (is (= (get-in body [(keyword repo-id) :commit])
                 (jgit-utils/head-rev-id-from-git-dir git-dir)))
          (is (nil? (get-in body [(keyword repo-id) :submodules])))))

      (testing "latest-commits returns the latest commits for published submodules"
        ; Publish the submodule
        (http-client/post publish-url)

        (let [response (http-client/post latest-commits-url {:as :text})
              body (common/parse-latest-commits-response response)
              submodule-commits (get-in body [(keyword repo-id) :submodules])]
          (is (= (get-in body [(keyword repo-id) :commit])
                 (jgit-utils/head-rev-id-from-git-dir git-dir)))
          (is (not (nil? submodule-commits)))
          (is (= (count (keys submodule-commits)) 1))
          (is (= submodule-commits
                 (jgit-utils/get-submodules-latest-commits git-dir working-dir))))))))

(defn get-commit
  [repo]
  (-> repo
      Git/open
      .log
      .call
      first))

(defn make-publish-request
  [body]
  (http-client/post publish-url
             {:body    (json/encode body)
              :headers {"Content-Type" "application/json"}}))

(deftest ^:integration publish-content-endpoint-success-test
  (testing "publish content endpoint makes correct commit"
    (let [repo "test-commit"
          repo2 "test-commit-2"
          working-dir (helpers/temp-dir-as-string)
          working-dir-2 (helpers/temp-dir-as-string)
          root-data-dir (helpers/temp-dir-as-string)
          data-dir (core/path-to-data-dir root-data-dir)
          server-repo (common/bare-repo data-dir repo)]

      (helpers/with-bootstrapped-storage-service
        app
        (helpers/storage-service-config
          root-data-dir
          {(keyword repo) {:working-dir working-dir}
           (keyword repo2) {:working-dir working-dir-2}})
        (testing "with no body supplied"
          (let [response (http-client/post publish-url)
                body (slurp (:body response))
                parsed-body (json/parse-string body)]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (contains? parsed-body repo))
              (is (contains? parsed-body repo2)))))

        (testing "with only repo-id supplied"
          (let [response (make-publish-request {:repo-id repo})
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body))
              (is (= 1 (count (keys (json/parse-string body))))))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= core/default-commit-message
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= core/default-commit-author-name (.getName (.getAuthorIdent commit))))
                (is (= core/default-commit-author-email
                       (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with just author supplied and repo-id supplied"
          (let [author {:name  "Tester"
                        :email "test@example.com"}
                response (make-publish-request {:author author
                                                :repo-id repo})
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

        (testing "with author, message and repo-id supplied"
          (let [author {:name  "Tester"
                        :email "test@example.com"}
                message "This is a test commit"
                response (make-publish-request {:author author
                                                :message message
                                                :repo-id repo})
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

(deftest ^:integration publish-content-endpoint-error-test
  (testing "publish content endpoint returns well-formed errors"
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        (helpers/temp-dir-as-string)
        {:repo-name {:working-dir (helpers/temp-dir-as-string)}})

      (testing "when request body does not match schema"
        (let [response (make-publish-request {:author "bad"})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "user-data-invalid" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when request body is malformed json"
        (let [response (http-client/post publish-url
                                  {:body "malformed"
                                   :headers {"Content-Type" "application/json"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "json-parse-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when request body is not json"
        (let [response (http-client/post publish-url {:body "not json"
                                                      :headers {"Content-Type" "text/plain"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "content-type-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body)))))))

(deftest ^:integration publish-content-endpoint-response-test
  (testing "publish content endpoint returns correct response"
    (let [failed-repo "publish-failed"
          success-repo "publish-success"
          working-dir-failed (helpers/temp-dir-as-string)
          working-dir-success (helpers/temp-dir-as-string)
          root-data-dir (helpers/temp-dir-as-string)
          data-dir (core/path-to-data-dir root-data-dir)]
      (helpers/with-bootstrapped-storage-service
        app
        (helpers/storage-service-config
          root-data-dir
          {(keyword failed-repo) {:working-dir working-dir-failed}
           (keyword success-repo) {:working-dir working-dir-success}})

        ; Delete the failed repo entirely - this'll cause the publish to fail
        (fs/delete-dir (common/bare-repo data-dir failed-repo))

        (with-test-logging
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (testing "get a 200 response"
              (is (= 200 (:status response))))

            (let [data (json/parse-string body)]
              (testing "for repo that was successfully published"
                (is (not= nil (get data success-repo)))
                (is (= (-> (common/bare-repo data-dir success-repo)
                           (jgit-utils/get-repository-from-git-dir)
                           (jgit-utils/head-rev-id))
                       (get-in data [success-repo "commit"]))
                    (str "Could not find correct body for " success-repo " in " body)))
              (testing "for repo that failed to publish"
                (is (re-matches #".*publish-error"
                                (get-in data [failed-repo "error" "type"]))
                    (str "Could not find correct body for " failed-repo " in " body))))))))))

(deftest ^:integration publish-endpoint-response-with-submodules-test
  (let [failed-parent "parent-failed"
        successful-parent "parent-success"
        submodule-1 "submodule-1"
        submodule-2 "submodule-2"
        submodule-3 "submodule-3"
        working-dir-failed (helpers/temp-dir-as-string)
        working-dir-success (helpers/temp-dir-as-string)
        root-data-dir (helpers/temp-dir-as-string)
        data-dir (core/path-to-data-dir root-data-dir)
        git-dir-success (common/bare-repo data-dir successful-parent)
        git-dir-failed (common/bare-repo data-dir failed-parent)
        submodules-dir-name-1 "submodules1"
        submodules-dir-name-2 "submodules2"
        submodules-working-dir-1 (helpers/temp-dir-as-string)
        submodules-working-dir-2 (helpers/temp-dir-as-string)]

  ;; set up submodules for "successful-parent"
  (ks/mkdirs! (fs/file submodules-working-dir-1 submodule-1))
  (helpers/write-test-file! (fs/file submodules-working-dir-1 submodule-1 "test.txt"))
  (ks/mkdirs! (fs/file submodules-working-dir-1 submodule-2))
  (helpers/write-test-file! (fs/file submodules-working-dir-1 submodule-2 "test.txt"))

  ;; set up submodule for "failed-parent"
  (ks/mkdirs! (fs/file submodules-working-dir-2 submodule-3))
  (helpers/write-test-file! (fs/file submodules-working-dir-2 submodule-3 "test.txt"))

  (helpers/with-bootstrapped-storage-service
    app
    (helpers/storage-service-config
      root-data-dir
      {(keyword successful-parent) {:working-dir working-dir-success
                                    :submodules-dir submodules-dir-name-1
                                    :submodules-working-dir submodules-working-dir-1}
       (keyword failed-parent) {:working-dir working-dir-failed
                                :submodules-dir submodules-dir-name-2
                                :submodules-working-dir submodules-working-dir-2}})

    (testing "successful publish returns SHAs for parent repos and submodules"
      (let [response (http-client/post publish-url)
            body (slurp (:body response))
            parsed-body (json/parse-string body)]
        (is (= 200 (:status response)))

        (is (= (jgit-utils/head-rev-id-from-git-dir git-dir-success)
              (get-in parsed-body [successful-parent "commit"])))
        (is (= (jgit-utils/get-submodules-latest-commits git-dir-success
                                                 working-dir-success)
              (get-in parsed-body [successful-parent "submodules"])))

        (is (= (jgit-utils/head-rev-id-from-git-dir git-dir-failed)
              (get-in parsed-body [failed-parent "commit"])))
        (is (= (jgit-utils/get-submodules-latest-commits git-dir-failed
                                                 working-dir-failed)
              (get-in parsed-body [failed-parent "submodules"])))))

    (testing "can specify a single submodule to be published"
      (let [submodules-orig-status (jgit-utils/get-submodules-latest-commits
                                     git-dir-success
                                     working-dir-success)
            submodule-1-commit (get submodules-orig-status
                                    (str submodules-dir-name-1 "/" submodule-1))
            submodule-2-commit (get submodules-orig-status
                                    (str submodules-dir-name-1 "/" submodule-2))
            response (make-publish-request {:repo-id successful-parent
                                            :submodule-id submodule-1})
            parsed-body (json/parse-string (slurp (:body response)))]

        (testing "publish was successful and returns correct commit for parent"
          (is (= 200 (:status response)))
          (is (= (-> git-dir-success
                     (jgit-utils/head-rev-id-from-git-dir))
                 (get-in parsed-body [successful-parent "commit"]))))

        (let [submodules-status (jgit-utils/get-submodules-latest-commits
                                  git-dir-success
                                  working-dir-success)
              submodule-name (str submodules-working-dir-1 "/" submodule-1)
              submodule-1-new-commit (get submodules-status
                                          (str submodules-dir-name-1 "/" submodule-1))
              submodule-2-new-commit (get submodules-status
                                          (str submodules-dir-name-1 "/" submodule-2))]

          (testing "only the specified submodule was published"
            (is (= (get submodules-status submodule-name)
                   (get-in parsed-body [successful-parent "submodules" submodule-name])))
            (is (= 1 (count (keys (get-in parsed-body [successful-parent "submodules"])))))
            (is (not= submodule-1-commit submodule-1-new-commit))
            (is (= submodule-2-commit submodule-2-new-commit))))))

    (testing "publish endpoint returns correct errors"
      (with-test-logging
        ; Delete a submodule repo entirely - this'll cause the publish to fail
        (fs/delete-dir (common/submodule-bare-repo data-dir successful-parent submodule-1))
        ; Delete a parent repo entirely - this'll cause the publish to fail
        (fs/delete-dir git-dir-failed)

        (let [response (http-client/post publish-url)
              body (slurp (:body response))
              parsed-body (json/parse-string body)]
          (is (= 200 (:status response)))

          (testing "when one submodule failed to publish, but parent repo succeeded"
            (let [successful-parent-repo (jgit-utils/get-repository
                                           git-dir-success working-dir-success)
                  submodules-status (.. (Git/wrap successful-parent-repo)
                                      submoduleStatus
                                      call)
                  successful-parent-body (get parsed-body successful-parent)]
              (testing "returns sha for non-failed submodules"
                (let [submodule-2-path (str submodules-dir-name-1 "/" submodule-2)]
                  (is (= (-> (get submodules-status submodule-2-path)
                           .getIndexId
                           .getName)
                        (get-in successful-parent-body ["submodules" submodule-2-path]))
                    (format "Could not find correct body for submodule %s of parent repo %s in %s"
                      submodule-2-path successful-parent body))))
              (testing "returns sha for parent repo"
                (is (= (jgit-utils/head-rev-id-from-git-dir git-dir-success)
                      (get successful-parent-body "commit"))))
              (testing "returns error for failed submodule"
                (let [submodule-1-path (str submodules-dir-name-1 "/" submodule-1)]
                (is (re-matches #".*publish-error"
                      (get-in successful-parent-body ["submodules" submodule-1-path "error" "type"]))
                  (format "Could not find correct body for submodule %s of parent repo %s in %s"
                    submodule-1-path successful-parent body))))))

          (testing "when parent repo failed to publish"
            (testing "returns error for parent repo"
              (is (re-matches #".*publish-error"
                    (get-in parsed-body [failed-parent "error" "type"]))
                (str "Could not find correct body for repo " failed-parent " in " body)))
            (testing "returns nothing for submodules"
              (is (= ["error"] (keys (parsed-body failed-parent))))))))))))

(deftest ^:integration submodules-test
  (testing "storage service works with submodules"
    (let [repo "parent-repo"
          submodules-working-dir (helpers/temp-dir-as-string)
          working-dir (helpers/temp-dir-as-string)
          submodules-dir-name "submodules"
          submodule-1 "existing-submodule"
          submodule-2 "nonexistent-submodule"
          root-data-dir (helpers/temp-dir-as-string)
          data-dir (core/path-to-data-dir root-data-dir)
          git-dir (common/bare-repo data-dir repo)]

      ;; Set up working directory for submodule-1, the "existing-submodule"
      ;; (that is, the submodule that exists before the storage service is
      ;; started). Note that it won't actually get set up as a submodule of
      ;; the parent repo until after a "publish" request is made.
      (ks/mkdirs! (fs/file submodules-working-dir submodule-1))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "test.txt"))

      (helpers/with-bootstrapped-storage-service
        app
        (helpers/storage-service-config
          root-data-dir
          {(keyword repo) {:working-dir working-dir
                           :submodules-dir submodules-dir-name
                           :submodules-working-dir submodules-working-dir}})

       (testing "parent repo initialized correctly but does not initialize any submodules"
         (is (fs/exists? git-dir))
         (is (not (fs/exists? (common/submodule-bare-repo data-dir repo submodule-1))))
         (is (not (fs/exists? (common/submodule-bare-repo data-dir repo submodule-2))))
         (let [submodules (jgit-utils/get-submodules-latest-commits git-dir working-dir)]
           (is (empty? submodules))))

       (testing "publish works and initializes submodule"
         ;; submodule-1 gets initialized because it already has a directory
         ;; within the `submodules-working-dir`, created during the setup step
         ;; before the storage service is started.
         (let [response (http-client/post publish-url)
               body (slurp (:body response))]
           (is (= 200 (:status response)))
           (is (fs/exists? (common/submodule-bare-repo data-dir repo submodule-1)))
           (is (not (fs/exists? (common/submodule-bare-repo data-dir repo submodule-2))))
           (is (= (jgit-utils/get-submodules-latest-commits git-dir working-dir)
                 (get-in (json/parse-string body) [repo "submodules"])))))

       (testing "adding a new submodule and triggering another publish"
         (ks/mkdirs! (fs/file submodules-working-dir submodule-2))
         (helpers/write-test-file! (fs/file submodules-working-dir submodule-2 "test.txt"))
         (let [response (http-client/post publish-url)
               body (slurp (:body response))]
           (is (= 200 (:status response)))
           (is (fs/exists? (common/submodule-bare-repo data-dir repo submodule-1)))
           (is (fs/exists? (common/submodule-bare-repo data-dir repo submodule-2)))
           (is (= (jgit-utils/get-submodules-latest-commits git-dir working-dir)
                 (get-in (json/parse-string body) [repo "submodules"])))))

       (testing "updating a submodule and triggering a publish"
         (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "update.txt"))
         (let [response (http-client/post publish-url)
               body (slurp (:body response))]
           (is (= 200 (:status response)))
           (is (= (jgit-utils/get-submodules-latest-commits git-dir working-dir)
                 (get-in (json/parse-string body) [repo "submodules"])))
           (is (fs/exists? (fs/file working-dir submodules-dir-name submodule-1 "update.txt")))
           (is (= (slurp (fs/file submodules-working-dir submodule-1 "update.txt"))
                 (slurp (fs/file working-dir submodules-dir-name submodule-1 "update.txt"))))))))))

(deftest ^:integration submodule-deletion-test
  (testing "storage service works with submodules"
    (let [repo "parent-repo"
          submodules-working-dir (helpers/temp-dir-as-string)
          working-dir (helpers/temp-dir-as-string)
          submodules-dir-name "submodules"
          submodule-1 "existing-submodule"
          submodule-2 "nonexistent-submodule"
          root-data-dir (helpers/temp-dir-as-string)
          data-dir (core/path-to-data-dir root-data-dir)
          git-dir (common/bare-repo data-dir repo)]


      (ks/mkdirs! (fs/file submodules-working-dir submodule-1))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "test.txt"))
      (ks/mkdirs! (fs/file submodules-working-dir submodule-2))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-2 "test.txt"))

      (helpers/with-bootstrapped-storage-service
        app
        (helpers/storage-service-config
          root-data-dir
          {(keyword repo) {:working-dir working-dir
                           :submodules-dir submodules-dir-name
                           :submodules-working-dir submodules-working-dir}})
        (let [response (http-client/post publish-url)
              body (slurp (:body response))]
          (testing "submodules successfully published"
            (is (= 200 (:status response)))
            (is (fs/exists? (common/submodule-bare-repo data-dir repo submodule-1)))
            (is (fs/exists? (common/submodule-bare-repo data-dir repo submodule-2)))
            (is (= (jgit-utils/get-submodules-latest-commits git-dir working-dir)
                  (get-in (json/parse-string body) [repo "submodules"])))))

        (testing "removing a submodule and triggering a publish"
          (fs/delete-dir (fs/file submodules-working-dir submodule-2))
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (is (= 200 (:status response)))
            (is (= (jgit-utils/get-submodules-latest-commits git-dir working-dir)
                  (get-in (json/parse-string body) [repo "submodules"])))
            (is (= 1 (count (get-in (json/parse-string body) [repo "submodules"]))))
            (is (= [(str submodules-dir-name "/" submodule-1)]
                  (jgit-utils/get-submodules (jgit-utils/get-repository git-dir working-dir))))

            (testing "bare repo for submodule is deleted by default"
              (is (fs/exists? (fs/file data-dir repo (str submodule-1 ".git"))))
              (is (not (fs/exists? (fs/file data-dir repo (str submodule-2 ".git")))))))))

      (testing "can preserve submodule bare repos on deletion"
        (helpers/with-bootstrapped-storage-service
          app
          (assoc-in
            (helpers/storage-service-config
              root-data-dir
              {(keyword repo) {:working-dir working-dir
                               :submodules-dir submodules-dir-name
                               :submodules-working-dir submodules-working-dir}})
            [:file-sync-storage :preserve-submodule-repos] true)

          (fs/delete-dir (fs/file submodules-working-dir submodule-1))
          (let [response (http-client/post publish-url)
                body (slurp (:body response))]
            (is (= 200 (:status response)))
            (is (= 0 (count (get-in (json/parse-string body) [repo "submodules"]))))
            (is (= 0 (count (jgit-utils/get-submodules (jgit-utils/get-repository git-dir working-dir)))))
            (is (fs/exists? (fs/file data-dir repo (str submodule-1 ".git"))))))))))

(defn fetch-status
  ([]
   (fetch-status nil))
  ([level]
   (http-client/get
     (str helpers/server-base-url
       (if level
         (str "/status/v1/services/file-sync-storage-service?level=" (name level))
         "/status/v1/services/file-sync-storage-service"))
     {:as :text})))

(defn response->status
  [response]
  (-> response
    :body
    json/parse-string
    (get "status")))

(deftest ^:integration status-endpoint-test
  (let [test-start-time (time/now)
        data-dir (helpers/temp-dir-as-string)
        working-dir (helpers/temp-dir-as-string)
        config (helpers/file-sync-config
                 data-dir
                 {:my-repo {:working-dir working-dir}})]
    (with-test-logging
      (bootstrap/with-app-with-config
        app
        helpers/file-sync-services-and-deps
        config

        ; First, create and publish some test content
        (spit (fs/file working-dir "test-file-1") "test file content 1")
        (spit (fs/file working-dir "test-file-2") "test file content 2")
        (let [publish-request-body (-> {:message "my msg"
                                        :author {:name "Testy"
                                                 :email "test@foo.com"}}
                                       json/generate-string)
              commit-id (-> (helpers/do-publish publish-request-body)
                            :body
                            (json/parse-string true)
                            :my-repo
                            :commit)]
          ; Block here until the sync process has run twice, which will cause
          ; two /latest-commits requests to be made to the server.
          ; The first request will not include a POST body, since the
          ; client has not yet cloned the repo from the server.
          ; The second request will, finally, POST the repo information back
          ; to the server, and at that point it will be availabe in the /status
          ; response and the test can continue.
          (dotimes [_ 2]
            (helpers/wait-for-new-state (helpers/get-sync-agent app)))
          (testing "A request against the Storage Service's status endpoint"
            (let [response (fetch-status)
                  body (json/parse-string (:body response))]
              (testing "Basic response data"
                (is (= 200 (:status response)))
                (is (= "file-sync-storage-service" (get body "service_name")))
                (is (= "running" (get body "state"))))
              (testing "The response should contain a timestamp"
                (is (time/within?
                      test-start-time
                      (time/now)
                      (helpers/parse-timestamp (get-in body ["status" "timestamp"])))))
              (let [repo-status (get-in body ["status" "repos" "my-repo"])
                    latest-commit-status (get repo-status "latest-commit")]
                (testing "Latest commit ID"
                  (is (= commit-id (get latest-commit-status "commit"))))
                (testing "Commit date/time"
                  (let [commit-time (time-format/parse (get latest-commit-status "date"))]
                    ; JGit tracks commit time at the seconds level of resolution,
                    ; (appears to just truncate milliseconds, never rounding up)
                    ; and therefore always has 0 milliseconds in the DateTime
                    ; object here.  So give it some wiggle room.
                    ;(is (time/after? commit-time test-start-time))
                    (is (time/after? commit-time (-> test-start-time
                                                     (time/minus (time/seconds 1)))))
                    (is (time/before? commit-time (time/now)))))
                (testing "The commit author"
                  (is (= {"name" "Testy"
                          "email" "test@foo.com"}
                         (get latest-commit-status "author"))))
                (testing "The commit message"
                  (is (= "my msg" (get latest-commit-status "message"))))
                (testing "Status of the working directory"
                  (is (= (get repo-status "working-dir")
                         {"clean" true
                          "missing" []
                          "modified" []
                          "untracked" []}))))
              (testing "The response should contain information about the clients"
                (let [clients (get-in body ["status" "clients"])]
                  (is (= 1 (count clients)))
                  (let [client-status (second (first clients))]
                    (testing "Repo information"
                      (is (= (get client-status "repos")
                            {"my-repo" {"latest-commit" commit-id
                                        "status" "synced"}})))
                    (testing "Last check-in timestamp"
                      (is (time/within?
                            test-start-time
                            (time/now)
                            (helpers/parse-timestamp (get client-status "last-check-in-time"))))))))
              (testing "The response should contain info about the latest publish"
                (let [latest-publish-status (get-in body ["status" "latest-publish"])]
                  (testing "Client IP address"
                    ; If this test runs on a machine in which 'localhost' resolves
                    ; to a different IP address, this assertion will fail.
                    ; If that ever actually happens, this should be changed
                    ; to simply test for a valid IP address.
                    ; For now, this test offers a little more assurance that
                    ; the IP address returned is the correct IP address.
                    (is (= (get latest-publish-status "client-ip-address")
                           "127.0.0.1")))
                  (testing "Timestamp"
                    (is (time/within?
                          test-start-time
                          (time/now)
                          (helpers/parse-timestamp (get latest-publish-status "timestamp")))))
                  (testing "Information about repos"
                    (is (= {"my-repo" {"commit" commit-id}}
                           (get latest-publish-status "repos")))))))))

        ; Now, we're going to muck up a bunch of stuff in the working directory
        ; in order to get an interesting response back.

        ; Modify an existing file.
        (spit (fs/file working-dir "test-file-1") "different file content")
        ; Delete an existing file
        (fs/delete (fs/file working-dir "test-file-2"))
        ; Create a new file.
        (spit (fs/file working-dir "new-test-file") "i like cheese")

        ; Get the status again
        (let [response (fetch-status)
              body (json/parse-string (:body response))
              status (get-in body ["status" "repos" "my-repo"])]
          (testing "The response from the /status endpoint"
            (testing "Status of the working directory"
              (is (= (get status "working-dir")
                     {"clean" false
                      "missing" ["test-file-2"]
                      "modified" ["test-file-1"]
                      "untracked" ["new-test-file"]})))))

        (testing "Status level is honored"
          (testing "level=critical doesn't return anything except the basics"
            (let [response (fetch-status :critical)]
              (is (= 200 (:status response)))
              (testing "No additional status data beyond state is returned"
                (is (= nil (response->status response))))))

          ; Don't need to test level=info - that's the default (what's used above).
          ; There's currently no debug-only status information.
          (testing "level=debug is same as level=info"
            (let [debug-response (fetch-status :debug)
                  info-response (fetch-status :info)]
              (is (= 200 (:status debug-response)))
              (is (= 200 (:status info-response)))
              (is (= (get (response->status debug-response) "repos")
                    (get (response->status info-response) "repos")))
              (is (= (get (response->status debug-response) "latest-publish")
                    (get (response->status info-response) "latest-publish"))))))))))

(deftest ^:integration submodules-status-endpoint-test
  (let [data-dir (helpers/temp-dir-as-string)
        working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules-dir"
        submodules-working-dir (helpers/temp-dir-as-string)
        submodule-name "my-submodule"
        submodule-path (str submodules-dir "/" submodule-name)
        config (helpers/storage-service-config
                 data-dir
                 {:my-repo {:working-dir working-dir
                            :submodules-dir submodules-dir
                            :submodules-working-dir submodules-working-dir}})]
    (helpers/with-bootstrapped-storage-service
      app config
      ; Create a submodule
      (fs/mkdirs (fs/file submodules-working-dir submodule-name))
      ; Write a file into the submodule
      (spit
        (fs/file submodules-working-dir submodule-name "test-file")
        "submodules are SO cool")
      (let [submodule-commit-id (-> (helpers/do-publish)
                                    :body
                                    json/parse-string
                                    (get-in [(name :my-repo)
                                             "submodules"
                                             submodule-path]))
            response (fetch-status)
            body (json/parse-string (:body response))]
        (is (= 200 (:status response)))
        (testing "The response should contain information about submodules status"
          (is (= (get-in body ["status" "repos" (name :my-repo) "submodules"])
                 {submodule-path {"head-id" submodule-commit-id
                                  "path" submodule-path
                                  "status" "INITIALIZED"}})))))))

