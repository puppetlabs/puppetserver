(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :refer :all]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
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

(defn temp-file-name
  "Returns a unique name to a temporary file, but does not actually create the file."
  [file-name-prefix]
  (fs/file (fs/tmpdir) (fs/temp-name file-name-prefix)))

(deftest apply-updates-to-repo-test
  (let [repo-name "apply-updates-test"
        server-repo-url (str helpers/server-repo-url "/" repo-name)
        client-repo-path (temp-file-name repo-name)
        config (helpers/storage-service-config-with-repos
                 (helpers/temp-dir-as-string)
                 {(keyword repo-name) {:working-dir repo-name}}
                 false)]
    (testing "Throws appropriate error when directory exists but has no git repo"
      (fs/mkdir client-repo-path)
      (is (thrown? IllegalStateException
                   (apply-updates-to-repo repo-name server-repo-url
                                          "" client-repo-path))))

    (testing "Throws appropriate slingshot error for a failed fetch"
      (helpers/init-bare-repo! client-repo-path)
      (is (thrown+-with-msg? map?
                             #"File sync was unable to fetch from server repo.*"
                             (apply-updates-to-repo repo-name server-repo-url
                                                    "" client-repo-path))))

    (testing "Throws appropriate slingshot error for a failed clone"
      (fs/delete-dir client-repo-path)
      (is (thrown+-with-msg? map?
                             #"File sync was unable to clone from server repo.*"
                             (apply-updates-to-repo repo-name server-repo-url
                                                    "" client-repo-path))))

    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app config
      (let [test-clone-dir (fs/file (helpers/temp-dir-as-string))
            test-clone-repo (.getRepository (jgit-utils/clone server-repo-url test-clone-dir))]
        (testing "Validate initial repo update"
          (fs/delete-dir client-repo-path)
          (let [initial-commit (jgit-utils/head-rev-id test-clone-repo)
                status (apply-updates-to-repo repo-name
                                              server-repo-url
                                              initial-commit
                                              client-repo-path)]
            (let [repo (jgit-utils/get-repository-from-git-dir client-repo-path)]
              (is (.isBare repo))
              (is (= initial-commit (jgit-utils/head-rev-id repo))))
            (is (= :synced (:status status)))
            (is (= initial-commit (:latest-commit status)))))
        (testing "Files fetched for update"
          (helpers/push-test-commit! test-clone-dir)
          (let [new-commit (jgit-utils/head-rev-id test-clone-repo)
                status (apply-updates-to-repo repo-name
                                              server-repo-url
                                              new-commit
                                              client-repo-path)]
            (is (= new-commit (jgit-utils/head-rev-id-from-git-dir client-repo-path)))
            (is (= :synced (:status status)))
            (is (= new-commit (:latest-commit status)))))
        (testing "No change when nothing pushed"
          (let [current-commit (jgit-utils/head-rev-id-from-git-dir client-repo-path)
                status (apply-updates-to-repo repo-name
                                              server-repo-url
                                              current-commit
                                              client-repo-path)]
            (is (= current-commit (jgit-utils/head-rev-id-from-git-dir client-repo-path)))
            (is (= :unchanged (:status status)))
            (is (= current-commit (:latest-commit status)))))
        (testing "Files restored after repo directory deleted"
          (let [commit-id (jgit-utils/head-rev-id-from-git-dir client-repo-path)]
            (fs/delete-dir client-repo-path)
            (let [status (apply-updates-to-repo repo-name
                                                server-repo-url
                                                commit-id
                                                client-repo-path)]
              (is (= (jgit-utils/head-rev-id-from-git-dir client-repo-path) commit-id))
              (is (= :synced (:status status)))
              (is (= commit-id (:latest-commit status))))))))))

(defn process-repos
  [repos client ssl? callbacks]
  (process-repos-for-updates
    repos
    (str (helpers/base-url ssl?) helpers/default-repo-path-prefix)
    (get-latest-commits-from-server
      client
      (str (helpers/base-url ssl?) helpers/default-api-path-prefix))
    callbacks))

(deftest process-repos-for-updates-test
  (let [server-base-path (helpers/temp-dir-as-string)
        client-target-repo-on-server (helpers/temp-dir-as-string)
        client-target-repo-nonexistent (helpers/temp-dir-as-string)
        client-target-repo-error (helpers/temp-dir-as-string)
        server-repo "process-repos-test"
        error-repo  "process-repos-error"
        client (sync/create-client {})
        server-repo-atom (atom {})
        nonexistent-repo-atom (atom false)
        server-repo-callback (fn [repo-id repo-state]
                               (swap! server-repo-atom
                                      #(assoc % :repo-id
                                                repo-id
                                                :repo-state
                                                repo-state)))
        nonexistent-repo-callback (fn [_ _]
                                    (reset! nonexistent-repo-atom true))]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/storage-service-config-with-repos
        server-base-path
        {(keyword server-repo) {:working-dir (helpers/temp-dir-as-string)}
         (keyword error-repo)  {:working-dir (helpers/temp-dir-as-string)}}
        false)
      (fs/delete-dir client-target-repo-on-server)
      (fs/delete-dir client-target-repo-nonexistent)
      (fs/delete-dir client-target-repo-error)
      (fs/delete-dir (fs/file server-base-path (str error-repo ".git")))

      (with-test-logging
        (let [state (process-repos {(keyword server-repo)           client-target-repo-on-server
                                    (keyword error-repo)            client-target-repo-error
                                    :process-repos-test-nonexistent client-target-repo-nonexistent}
                                   client false
                                   (atom {(keyword server-repo) server-repo-callback
                                          :nonexistent-repo     nonexistent-repo-callback}))]
          (testing "process-repos-for-updates returns correct state info"
            (is (= (get state server-repo) {:status        :synced
                                            :latest-commit nil}))
            (is (= :failed (get-in state [error-repo :status])))
            (is (= :puppetlabs.enterprise.services.file-sync-client.file-sync-client-core/error
                   (get-in state [error-repo :cause :type])))
            (is (not (nil? (get-in state [error-repo :cause :message]))))))

        (testing "Client directory created when match on server"
          (is (fs/exists? client-target-repo-on-server)))

        (testing "Client directory not created when no match on server"
          (is (not (fs/exists? client-target-repo-nonexistent))
              "Found client directory despite no matching repo on server")
          (is
            (logged?
              #"^File sync did not find.*process-repos-test-nonexistent"
              :error)))

        (testing "Error logged when repo cannot be synced"
          (is (logged?
                #"^Error syncing repo.*"
                :error)))

        (testing "Repo callback called when match on server"
          (let [status @server-repo-atom]
            (is (= (keyword server-repo) (:repo-id status)))
            (is (= :synced (get-in status [:repo-state :status])))
            (is (= nil (get-in status [:repo-state :latest-commit])))))

        (testing "Repo callback not called when no match on server"
          (is (= false @nonexistent-repo-atom)))))))

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
