(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
              :as core]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.enterprise.jgit-client :as client]
            [me.raynes.fs :as fs]))

(deftest get-body-from-latest-commits-payload-test
  (testing "Can get latest commits"
    (is (= {"repo1" "123456", "repo2" nil}
           (core/get-body-from-latest-commits-payload
             {:headers {"content-type" "application/json"}
              :body "{\"repo1\": \"123456\", \"repo2\":null}"}))
        "Unexpected body received for get request"))
  (testing "Can't get latest commits for bad content type"
    (is (thrown-with-msg?
          Exception
          #"^Did not get json response for latest-commits.*"
          (core/get-body-from-latest-commits-payload
            {:headers {"content-type" "bogus"}
             :body    "{\"repo1\": \"123456\""}))))
  (testing "Can't get latest commits for no body"
    (is (thrown-with-msg?
          Exception
          #"^Did not get response body for latest-commits.*"
          (core/get-body-from-latest-commits-payload
            {:headers {"content-type" "application/json"}})))))

(defn process-repos-and-verify
  [repos-to-verify client]
  (core/process-repos-for-updates
    (str helpers/server-base-url helpers/default-repo-path-prefix)
    (str helpers/server-base-url helpers/default-api-path-prefix)
    (into-array (map #(:process-repo %) repos-to-verify))
    client)
  (doseq [repo repos-to-verify]
    (let [target-dir (get-in repo [:process-repo :target-dir])]
      (is (= (client/head-rev-id (:origin-dir repo))
             (client/head-rev-id target-dir))
          (str "Unexpected head revision in target repo directory : "
               target-dir)))))

(deftest process-repos-for-updates-test
  (let [git-base-dir          (helpers/temp-dir-as-string)
        server-repo-subpath-1 "process-repos-test-1.git"
        server-repo-subpath-2 "process-repos-test-2.git"
        server-repo-subpath-3 "process-repos-test-3.git"]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/jgit-plaintext-config-with-repos
        git-base-dir
        [{:sub-path server-repo-subpath-1}
         {:sub-path server-repo-subpath-2}
         {:sub-path server-repo-subpath-3}])
      (let [client-orig-repo-dir-1 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-1)
            client-orig-repo-dir-2 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-2
                                     2)
            client-orig-repo-dir-3 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-3
                                     0)
            client-targ-repo-dir-1 (helpers/temp-dir-as-string)
            client-targ-repo-dir-2 (helpers/temp-dir-as-string)
            client-targ-repo-dir-3 (helpers/temp-dir-as-string)
            repos-to-verify        [{:origin-dir    client-orig-repo-dir-1
                                     :process-repo {
                                       :name        server-repo-subpath-1
                                       :target-dir  client-targ-repo-dir-1}}
                                    {:origin-dir    client-orig-repo-dir-2
                                     :process-repo {
                                       :name        server-repo-subpath-2
                                       :target-dir  client-targ-repo-dir-2}}
                                    {:origin-dir    client-orig-repo-dir-3
                                     :process-repo {
                                       :name        server-repo-subpath-3
                                       :target-dir  client-targ-repo-dir-3}}]
            client (sync/create-client {})]
        (testing "Validate initial repo update"
          (fs/delete-dir client-targ-repo-dir-1)
          (fs/delete-dir client-targ-repo-dir-2)
          (fs/delete-dir client-targ-repo-dir-3)
          (process-repos-and-verify repos-to-verify client))
        (testing "Files pulled for update"
          (helpers/create-and-push-file client-orig-repo-dir-2)
          (helpers/create-and-push-file client-orig-repo-dir-3)
          (process-repos-and-verify repos-to-verify client))
        (testing "No change when nothing pushed"
          (process-repos-and-verify repos-to-verify client))
        (testing "Files restored after repo directory deleted"
          (fs/delete-dir client-targ-repo-dir-2)
          (process-repos-and-verify repos-to-verify client))
        (testing "Client directory not created when no match on server"
          (logging/with-test-logging
            (let [client-targ-repo-nonexistent (helpers/temp-dir-as-string)]
              (fs/delete-dir client-targ-repo-nonexistent)
              (core/process-repos-for-updates
                (str helpers/server-base-url helpers/default-repo-path-prefix)
                (str helpers/server-base-url helpers/default-api-path-prefix)
                [{:name       "process-repos-test-nonexistent.git"
                  :target-dir client-targ-repo-nonexistent}]
                client)
              (is (not (fs/exists? client-targ-repo-nonexistent))
                  "Found client directory despite no matching repo on server")
              (is
                (logged?
                  #"^File sync did not find.*process-repos-test-nonexistent.git"
                  :error)))))))))