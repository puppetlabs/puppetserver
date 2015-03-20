(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core-test
  (:import (clojure.lang ExceptionInfo)
           (org.eclipse.jgit.api Git)
           (org.eclipse.jgit.api.errors GitAPIException))
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.enterprise.file-sync-test-utils
             :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
             :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.enterprise.jgit-client :as client]))

(use-fixtures :once schema-test/validate-schemas)

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
  (sync/post publish-url
             {:body (json/encode body)
              :headers {"Content-Type" "application/json"}}))

(defn validate-receive-pack-setting
  [repo-dir]
  (let [http-receive-pack (-> (fs/file repo-dir)
                              (Git/open)
                              (.getRepository)
                              (.getConfig)
                              (.getInt "http" "receivepack" -2))]
    (is (= 1 http-receive-pack)
        (str "Http receive pack was not set to 1 during initialization "
             "for repo-dir: "
             repo-dir))))

(defn validate-exception-info-for-initialize-repos!
  [message config]
  (is (thrown-with-msg?
        ExceptionInfo
        message
        (initialize-repos! config))))

(defn get-http-recievepack
  [repo]
  (-> repo
      (client/get-repository-from-git-dir)
      (.getConfig)
      (.getInt "http" "receivepack" (Integer/MIN_VALUE))))

(deftest initialize-repo!-test
  (testing "The repo's 'http.receivepack' setting should be 0 when the
           'allow-anonymous-push?' parameter is false."
    (let [repo (ks/temp-dir)]
      (initialize-repo! repo false)
      (let [receivepack (get-http-recievepack repo)]
        (is (= 0 receivepack)))))

  (testing "The repo's 'http.receivepack' setting should be 1 when the
           'allow-anonymous-push?' parameter is true."
    (let [repo (ks/temp-dir)]
    (initialize-repo! repo true)
    (let [receivepack (get-http-recievepack repo)]
      (is (= 1 receivepack))))))

(deftest initialize-repos!-test
  (let [base-dir (fs/file (ks/temp-dir) "base")
        repos {:sub1 {:working-dir "sub1-dir"}
               :sub2 {:working-dir "sub2-dir"}
               :sub3 {:working-dir "sub3-dir/subsub3"}}
        config   (helpers/file-sync-storage-config-payload
                   (.getPath base-dir)
                   repos)]
    (testing "Vector of repos can be initialized"
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (validate-receive-pack-setting (fs/file base-dir sub-path))))
    (testing "Content in repos not wiped out during reinitialization"
      (doseq [sub-path (map name (keys repos))]
        (let [file-to-check (fs/file base-dir sub-path (str sub-path ".txt"))]
          (ks/mkdirs! (.getParentFile file-to-check))
          (fs/touch file-to-check)))
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (let [file-to-check (fs/file base-dir sub-path (str sub-path ".txt"))]
          (is (fs/exists? file-to-check)
            (str "Expected file missing after repo reinitialization: "
                 file-to-check)))))
    (testing "Http receive pack for repos restored to 1 after reinitialization"
      (doseq [sub-path (map name (keys repos))]
        (fs/delete (fs/file base-dir sub-path "config")))
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (validate-receive-pack-setting (fs/file base-dir sub-path))))
    (testing "ExceptionInfo thrown for missing base-path in config"
      (validate-exception-info-for-initialize-repos!
        #":base-path missing-required-key"
        (dissoc config :base-path)))
    (testing "ExceptionInfo thrown for missing repos in config"
      (validate-exception-info-for-initialize-repos!
        #":repos missing-required-key"
        (dissoc config :repos)))
    (testing "ExceptionInfo thrown for missing repos sub-dir in config"
      (validate-exception-info-for-initialize-repos!
        #":working-dir missing-required-key"
        (assoc config :repos {:test-repo-name {}})))))

(deftest publish-content-endpoint-success-test
  (testing "publish content endpoint makes correct commit"
    (let [repo "test-commit.git"
          working-dir (helpers/temp-dir-as-string)
          base-path (helpers/temp-dir-as-string)
          server-repo (fs/file base-path repo)]

      (helpers/add-remote! (helpers/init-repo! (fs/file working-dir))
                          "origin"
                          (str (helpers/repo-base-url) "/" repo))

      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          base-path
          {(keyword repo) {:working-dir working-dir}}
          false)
        (testing "with no body supplied"
          (let [response (sync/post publish-url)
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= "Publish content to file sync storage service"
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= "PE File Sync Service" (.getName (.getAuthorIdent commit))))
                (is (= "" (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with just author supplied"
          (let [author {:name "Tester"
                        :email "test@example.com"}
                response (make-publish-request {:author author})
                body (slurp (:body response))]
            (testing "get expected response"
              (is (= (:status response) 200))
              (is (= repo (first (keys (json/parse-string body))))
                  (str "Unexpected response body: " body)))
            (let [commit (get-commit server-repo)]
              (testing "commit message is correct"
                (is (= "Publish content to file sync storage service"
                       (.getFullMessage commit))))
              (testing "commit author is correct"
                (is (= (:name author) (.getName (.getAuthorIdent commit))))
                (is (= (:email author) (.getEmailAddress (.getAuthorIdent commit))))))))

        (testing "with author and message supplied"
          (let [author {:name "Tester"
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

      (testing "when body does not match schema"
        (let [response (make-publish-request {:author "bad"})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "schema-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when body is malformed json"
        (let [response (sync/post publish-url
                                  {:body "malformed"
                                   :headers {"Content-Type" "application/json"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "json-parse-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body))))

      (testing "when body is not json"
        (let [response (sync/post publish-url {:body "not json"
                                               :headers {"Content-Type" "text/plain"}})
              body (slurp (:body response))]
          (is (= (:status response) 400))
          (is (= "json-parse-error" (get-in (json/parse-string body) ["error" "type"]))
              (str "Unexpected response body: " body)))))))

(deftest publish-content-endpoint-response-test
  (testing "publish content endpoint returns correct response"
    (let [failed-repo "publish-failed.git"
          nonexistent-repo "publish-non-existent.git"
          success-repo "publish-success.git"
          working-dir-failed (helpers/temp-dir-as-string)
          working-dir-success (helpers/temp-dir-as-string)
          base-path (helpers/temp-dir-as-string)]

      (helpers/init-repo! (fs/file working-dir-failed))
      (helpers/add-remote! (helpers/init-repo! (fs/file working-dir-success))
                          "origin"
                          (str (helpers/repo-base-url) "/" success-repo))

      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/storage-service-config-with-repos
          base-path
          {(keyword failed-repo) {:working-dir working-dir-failed}
           (keyword nonexistent-repo) {:working-dir "not/a/directory"}
           (keyword success-repo) {:working-dir working-dir-success}}
          false)
        (with-test-logging
          (let [response (sync/post publish-url)
                body (slurp (:body response))]
            (testing "get a 200 response"
              (is (= (:status response) 200)))

            (let [data (json/parse-string body)]
              (testing "for repo that was successfully published"
                (is (not= nil (get data success-repo)))
                (is (= (client/head-rev-id-from-working-tree (fs/file working-dir-success))
                       (get data success-repo))
                    (str "Could not find correct body for " failed-repo " in " body)))
              (testing "for nonexistent repo"
                (is (= "puppetlabs.enterprise.file-sync-storage/repo-not-found-error"
                       (get-in data [nonexistent-repo "error" "type"]))
                    (str "Could not find correct body for " nonexistent-repo " in " body)))
              (testing "for repo that failed to publish"
                (is (= "puppetlabs.enterprise.file-sync-storage/publish-error"
                       (get-in data [failed-repo "error" "type"]))
                    (str "Could not find correct body for " failed-repo " in " body))))))))))
