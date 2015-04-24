(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :refer :all]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [me.raynes.fs :as fs]
            [slingshot.test :refer :all]
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
    (is (thrown+-with-msg? map?
                           #"Response for latest commits unexpected.*content-type.*bogus.*"
                           (get-body-from-latest-commits-payload
                             {:status  200
                              :headers {"content-type" "bogus"}
                              :body    "{\"repo1\": \"123456\""}))))

  (testing "Can't get latest commits for no body"
    (is (thrown+-with-msg? map?
                           #"Response for latest commits unexpected.*body.*missing.*"
                           (get-body-from-latest-commits-payload
                             {:status  200
                              :headers {"content-type" "application/json"}})))))

(deftest apply-updates-to-repo-test
  (let [client-target-repo (fs/file (helpers/temp-dir-as-string))
        repo-name "apply-updates-test"
        repo-url (str helpers/server-repo-url "/" repo-name)]

    (testing "Throws appropriate error when directory exists but has no git repo"
      (is (thrown? IllegalStateException
                   (apply-updates-to-repo! repo-name repo-url "" client-target-repo))))

    (testing "Throws appropriate slingshot error for a failed fetch"
      (helpers/init-bare-repo! client-target-repo)
      (is (thrown+-with-msg? map?
                             #"File sync was unable to fetch from server repo.*"
                             (apply-updates-to-repo! repo-name repo-url "" client-target-repo))))

    (testing "Throws appropriate slingshot error for a failed clone"
      (fs/delete-dir client-target-repo)
      (is (thrown+-with-msg? map?
                             #"File sync was unable to clone from server repo.*"
                             (apply-updates-to-repo! repo-name repo-url "" client-target-repo))))))

(defn temp-file-name
  "Returns a unique name to a temporary file, but does not actually create the file."
  [file-name-prefix]
  (fs/file (fs/tmpdir) (fs/temp-name file-name-prefix)))

(deftest process-repo-for-updates-test
  (let [repo-name "process-repo-test"
        server-repo-url (str helpers/server-repo-url "/" repo-name)
        client-repo-path (temp-file-name repo-name)
        config (helpers/storage-service-config-with-repos
                 (helpers/temp-dir-as-string)
                 {(keyword repo-name) {:working-dir repo-name}}
                 false)]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app config
      (let [test-clone-dir (fs/file (helpers/temp-dir-as-string))
            test-clone-repo (.getRepository (jgit-client/clone server-repo-url test-clone-dir))]
        (testing "Validate initial repo update"
          (let [initial-commit (jgit-client/head-rev-id test-clone-repo)]
            (process-repo-for-updates! helpers/server-repo-url
                                       repo-name
                                       client-repo-path
                                       initial-commit)
            (let [repo (jgit-client/get-repository-from-git-dir client-repo-path)]
              (is (.isBare repo))
              (is (= initial-commit (jgit-client/head-rev-id repo))))))
        (testing "Files fetched for update"
          (helpers/push-test-commit! test-clone-dir)
          (let [new-commit (jgit-client/head-rev-id test-clone-repo)]
            (process-repo-for-updates! helpers/server-repo-url
                                       repo-name
                                       client-repo-path
                                       new-commit)
            (is (= new-commit (jgit-client/head-rev-id-from-git-dir client-repo-path)))))
        (testing "No change when nothing pushed"
          (let [current-commit (jgit-client/head-rev-id-from-git-dir client-repo-path)]
            (process-repo-for-updates! helpers/server-repo-url
                                       repo-name
                                       client-repo-path
                                       current-commit)
            (is (= current-commit (jgit-client/head-rev-id-from-git-dir client-repo-path)))))
        (testing "Files restored after repo directory deleted"
          (let [commit-id (jgit-client/head-rev-id-from-git-dir client-repo-path)]
            (fs/delete-dir client-repo-path)
            (process-repo-for-updates! helpers/server-repo-url
                                       repo-name
                                       client-repo-path
                                       commit-id)
            (is (= (jgit-client/head-rev-id-from-git-dir client-repo-path) commit-id))))))))

(defn process-repos
  [repos client ssl?]
  (process-repos-for-updates!
    client
    (str (helpers/base-url ssl?) helpers/default-repo-path-prefix)
    (str (helpers/base-url ssl?) helpers/default-api-path-prefix)
    repos))

(deftest process-repos-for-updates-test
  (let [client-target-repo-on-server (helpers/temp-dir-as-string)
        client-target-repo-nonexistent (helpers/temp-dir-as-string)
        server-repo "process-repos-test"
        client (sync/create-client {})]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/storage-service-config-with-repos
        (helpers/temp-dir-as-string)
        {(keyword server-repo) {:working-dir server-repo}}
        false)
      (fs/delete-dir client-target-repo-on-server)
      (fs/delete-dir client-target-repo-nonexistent)

      (with-test-logging
        (process-repos {(keyword server-repo) client-target-repo-on-server
                        :process-repos-test-nonexistent client-target-repo-nonexistent}
                       client false)

        (testing "Client directory created when match on server"
          (is (fs/exists? client-target-repo-on-server)))

        (testing "Client directory not created when no match on server"
          (is (not (fs/exists? client-target-repo-nonexistent))
              "Found client directory despite no matching repo on server")
          (is
            (logged?
              #"^File sync did not find.*process-repos-test-nonexistent"
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
