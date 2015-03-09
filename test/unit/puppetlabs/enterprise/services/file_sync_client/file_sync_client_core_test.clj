(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
             :refer :all]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.enterprise.jgit-client :as client]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [try+]]
            [schema.test :as schema-test])
  (:import (org.eclipse.jgit.transport HttpTransport)
           (java.net URL)
           (com.puppetlabs.enterprise HttpClientConnection)))

(use-fixtures :once schema-test/validate-schemas)

(deftest get-body-from-latest-commits-payload-test
  (testing "Can get latest commits"
    (is (= {"repo1" "123456", "repo2" nil}
           (get-body-from-latest-commits-payload
             {:status 200
              :headers {"content-type" "application/json"}
              :body "{\"repo1\": \"123456\", \"repo2\":null}"}))
        "Unexpected body received for get request"))

  (testing "Can't get latest commits for bad content type"
    (let [got-expected-error? (atom false)]
      (try+
        (get-body-from-latest-commits-payload
          {:status  200
           :headers {"content-type" "bogus"}
           :body    "{\"repo1\": \"123456\""})
        (catch map? error
          (reset! got-expected-error? true)
          (is (re-matches #"Response for latest commits unexpected.*content-type.*bogus.*"
                          (:message error)))))
      (is @got-expected-error?)))

  (testing "Can't get latest commits for no body"
    (let [got-expected-error? (atom false)]
      (try+
        (get-body-from-latest-commits-payload
          {:status  200
           :headers {"content-type" "application/json"}})
        (catch map? error
          (reset! got-expected-error? true)
          (is (re-matches #"Response for latest commits unexpected.*body.*missing.*"
                          (:message error)))))
      (is @got-expected-error?))))

(deftest process-repo-for-updates-test
  (let [client-target-repo (fs/file (helpers/temp-dir-as-string))
        repo-name "process-repo-test.git"
        update-repo (fn [commit-id]
                      (process-repo-for-updates helpers/server-repo-url
                                                     repo-name
                                                     client-target-repo
                                                     commit-id))]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/jgit-config-with-repos
        (helpers/temp-dir-as-string)
        [{:sub-path repo-name}]
        false)
      (let [local-repo-dir (fs/file (helpers/temp-dir-as-string))
            local-repo (.getRepository (helpers/clone-and-validate
                                         (str helpers/server-repo-url "/" repo-name)
                                         local-repo-dir))]
        (testing "Validate initial repo update"
          (fs/delete-dir client-target-repo)
          (let [initial-commit (client/head-rev-id local-repo)]
            (update-repo initial-commit)
            (let [repo (client/get-repository-from-git-dir client-target-repo)]
              (is (.isBare repo))
              (is (= initial-commit (client/head-rev-id repo))))))
        (testing "Files fetched for update"
          (helpers/create-and-push-file local-repo-dir)
          (let [new-commit (client/head-rev-id local-repo)]
            (update-repo new-commit)
            (is (= new-commit (client/head-rev-id-from-git-dir client-target-repo)))))
        (testing "No change when nothing pushed"
          (let [current-commit (client/head-rev-id-from-git-dir client-target-repo)]
            (update-repo current-commit)
            (is (= current-commit (client/head-rev-id-from-git-dir client-target-repo)))))
        (testing "Files restored after repo directory deleted"
          (let [commit-id (client/head-rev-id-from-git-dir client-target-repo)]
            (fs/delete-dir client-target-repo)
            (update-repo commit-id)
            (is (= (client/head-rev-id-from-git-dir client-target-repo) commit-id))))))))

(deftest process-repos-for-updates-test
  (let [client-target-repo-on-server (helpers/temp-dir-as-string)
        client-target-repo-nonexistent (helpers/temp-dir-as-string)
        server-repo "process-repos-test.git"
        client (sync/create-client {})]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/jgit-config-with-repos
        (helpers/temp-dir-as-string)
        [{:sub-path server-repo}]
        false)
      (fs/delete-dir client-target-repo-on-server)
      (fs/delete-dir client-target-repo-nonexistent)

      (with-test-logging
        (process-repos-for-updates
          client
          (str (helpers/base-url false) helpers/default-repo-path-prefix)
          (str (helpers/base-url false) helpers/default-api-path-prefix)
          {(keyword server-repo) client-target-repo-on-server
           :process-repos-test-nonexistent.git client-target-repo-nonexistent})

        (testing "Client directory created when match on server"
          (is (fs/exists? client-target-repo-on-server)))

        (testing "Client directory not created when no match on server"
          (is (not (fs/exists? client-target-repo-nonexistent))
              "Found client directory despite no matching repo on server")
          (is
            (logged?
              #"^File sync did not find.*process-repos-test-nonexistent.git"
              :error)))))))

(deftest ssl-configuration-test
  (testing "JGit's ConnectionFactory hands-out instances of our own
            HttpClientConnection after SSL has been configured."
    (configure-jgit-client-ssl! helpers/ssl-context)
    (let [connection-factory (HttpTransport/getConnectionFactory)
          connection (.create connection-factory (URL. "https://localhost:10080"))]
      (is (instance? HttpClientConnection connection)))))

(deftest fatal-error-test
  (testing "The agent attempts to shutdown the entire application when a
            fatal error occurs"
    (let [shutdown-atom (atom false)
          shutdown-fn #(reset! shutdown-atom true)
          sync-agent (create-agent shutdown-fn)
          disaster (fn [& _] (throw (new Throwable)))
          my-promise (promise)]
      (helpers/add-watch-and-deliver-new-state shutdown-atom my-promise)
      (with-test-logging
        (send sync-agent disaster))
      (let [shutdown-requested? (deref my-promise)]
        ; These two assertions are equivalent.
        (is shutdown-requested?)
        (is @shutdown-atom)))))
