(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :refer :all]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as storage-core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [me.raynes.fs :as fs]
            [slingshot.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.enterprise.file-sync-common :as common])
  (:import (org.eclipse.jgit.transport HttpTransport)
           (java.net URL)
           (com.puppetlabs.enterprise HttpClientConnection)))

(use-fixtures :once schema-test/validate-schemas)

(deftest get-body-from-latest-commits-payload-test
  (testing "Can get latest commits"
    (is (= {:repo1 {:commit "123456"
                    :submodules {}}, :repo2 nil}
           (get-body-from-latest-commits-payload
             {:status 200
              :headers {"content-type" "application/json"}
              :body "{\"repo1\": {\"commit\": \"123456\", \"submodules\": {}}, \"repo2\":null}"}))
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
        root-data-dir (helpers/temp-dir-as-string)
        storage-data-dir (storage-core/path-to-data-dir root-data-dir)
        config (helpers/storage-service-config
                 root-data-dir
                 {(keyword repo-name) {:working-dir repo-name}})]
    (testing "Throws appropriate error when non-empty directory exists but has no git repo"
      (fs/mkdir client-repo-path)
      (fs/touch (fs/file client-repo-path "foo"))
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

    (helpers/with-bootstrapped-storage-service
      app config
      (let [test-clone-dir (fs/file (helpers/temp-dir-as-string))
            test-clone-repo (.getRepository (helpers/clone-from-data-dir
                                              storage-data-dir repo-name test-clone-dir))]
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
              (is (= commit-id (:latest-commit status))))))
        (testing "Can clone into existing empty directory"
          (fs/delete-dir client-repo-path)
          (is (not (fs/exists? client-repo-path)))
          (fs/mkdir client-repo-path)
          (let [status (apply-updates-to-repo repo-name
                         server-repo-url
                         nil
                         client-repo-path)]
            (is (= :synced (:status status)))))))))

(def publish-url
  (str helpers/server-base-url
       helpers/default-api-path-prefix
       "/v1"
       common/publish-content-sub-path))

(deftest process-submodules-for-updates-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        storage-data-dir (storage-core/path-to-data-dir root-data-dir)
        client-data-dir (path-to-data-dir root-data-dir)
        server-repo "process-repos-test"
        client-target-repo-on-server (str client-data-dir "/" server-repo ".git")
        submodules-root (str client-data-dir "/" server-repo)
        client (sync/create-client {})
        submodules-working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodule-1 "submodule-1"
        submodule-2 "submodule-2"
        submodule-1-dir (fs/file storage-data-dir
                                 (str server-repo ".git")
                                 (str submodule-1 ".git"))
        submodule-2-dir (fs/file storage-data-dir
                                 (str server-repo ".git")
                                 (str submodule-2 ".git"))]
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        root-data-dir
        {(keyword server-repo) {:working-dir (helpers/temp-dir-as-string)
                                :submodules-dir submodules-dir
                                :submodules-working-dir submodules-working-dir}})

      ;; Create the submodules and add commits
      (ks/mkdirs! (fs/file submodules-working-dir submodule-1))
      (ks/mkdirs! (fs/file submodules-working-dir submodule-2))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "test.txt"))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-2 "test.txt"))
      (http-client/post publish-url)

      (with-test-logging
        (testing "submodules are successfully synced"
          (let [latest-commits (get-latest-commits-from-server client
                                                               (str (helpers/base-url false)
                                                                    helpers/default-api-path-prefix
                                                                    "/v1")
                                                               {:status :created})
                latest-commit (get latest-commits (keyword server-repo))
                submodules-status (process-submodules-for-updates
                                    (str (helpers/base-url false)
                                         helpers/default-repo-path-prefix
                                         "/"
                                         server-repo)
                                    (:submodules latest-commit)
                                    submodules-root)
                submodule-1-status (get submodules-status
                                        (str submodules-dir "/" submodule-1))
                submodule-2-status (get submodules-status
                                        (str submodules-dir "/" submodule-2))]
            (is (fs/exists? (fs/file submodules-root (str submodule-1 ".git"))))
            (is (= :synced (:status submodule-1-status)))
            (is (= (jgit-utils/head-rev-id-from-git-dir submodule-1-dir)
                   (:commit submodule-1-status)))
            (is (fs/exists? (fs/file submodules-root (str submodule-2 ".git"))))
            (is (= :synced (:status submodule-2-status)))
            (is (= (jgit-utils/head-rev-id-from-git-dir submodule-2-dir)
                   (:commit submodule-2-status)))))))))

(defn process-repos
  [repos callbacks data-dir]
  (let [latest-commits-url (str (helpers/base-url)
                             helpers/default-api-path-prefix
                             "/v1"
                             common/latest-commits-sub-path)
        body (-> latest-commits-url
                 (http-client/post {:as :text})
                 get-body-from-latest-commits-payload)]
    (process-repos-for-updates
      repos
      (str (helpers/base-url) helpers/default-repo-path-prefix)
      body
      callbacks
      data-dir)))

(deftest process-repos-for-updates-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        storage-data-dir (storage-core/path-to-data-dir root-data-dir)
        client-data-dir (path-to-data-dir root-data-dir)
        server-repo "process-repos-test"
        error-repo  "process-repos-error"
        nonexistent-repo "process-repos-test-nonexistent"
        client-target-repo-on-server (str client-data-dir "/" server-repo ".git")
        client-target-repo-nonexistent (str client-data-dir "/" nonexistent-repo ".git")
        client-target-repo-error (str client-data-dir "/" error-repo ".git")
        server-repo-atom (atom {})
        nonexistent-repo-atom (atom false)
        server-repo-callback (fn [repo-id repo-state]
                               (swap! server-repo-atom
                                      #(assoc % :repo-id
                                                repo-id
                                                :repo-state
                                                repo-state)))
        nonexistent-repo-callback (fn [_ _]
                                    (reset! nonexistent-repo-atom true))
        submodules-working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodule "submodule"]
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        root-data-dir
        {(keyword server-repo) {:working-dir (helpers/temp-dir-as-string)
                                :submodules-dir submodules-dir
                                :submodules-working-dir submodules-working-dir}
         (keyword error-repo)  {:working-dir (helpers/temp-dir-as-string)}})
      (ks/mkdirs! (fs/file submodules-working-dir submodule))
      (http-client/post publish-url)
      (fs/delete-dir client-target-repo-on-server)
      (fs/delete-dir client-target-repo-nonexistent)
      (fs/delete-dir client-target-repo-error)
      (fs/delete-dir (fs/file storage-data-dir (str error-repo ".git")))

      (with-test-logging
        (let [state (process-repos [server-repo error-repo nonexistent-repo]
                                   {(keyword server-repo) server-repo-callback
                                    :nonexistent-repo     nonexistent-repo-callback}
                                   client-data-dir)]
          (testing "process-repos-for-updates returns correct state info"
            (is (= (get-in state [server-repo :status]) :synced))
            (is (not (nil? (get-in state [server-repo :latest-commit]))))
            (is (not (nil? (get-in state [server-repo :submodules
                                          (str submodules-dir "/" submodule)
                                          :latest-commit]))))
            (is (= :failed (get-in state [error-repo :status])))
            (is (= :puppetlabs.enterprise.services.file-sync-client.file-sync-client-core/error
                   (get-in state [error-repo :cause :type])))
            (is (not (nil? (get-in state [error-repo :cause :message]))))))

        (testing "Client directory created when match on server"
          (is (fs/exists? client-target-repo-on-server)))

        (testing "Client submodule directories created when match on server"
          (is (fs/exists? (fs/file client-data-dir server-repo (str submodule ".git")))))

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
            (is (not= nil (get-in status [:repo-state :latest-commit])))))

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

(deftest register-callback-test
  (testing "register-callback! throws an error if callback is not a function"
    (is (thrown?
          IllegalArgumentException
          (register-callback! {} :test 123)))))

(deftest sync-working-dir-test
  (let [client-data-dir (helpers/temp-dir-as-string)
        repo "test-repo"
        git-dir (str client-data-dir "/" repo ".git")
        working-dir (helpers/temp-dir-as-string)
        local-repo-dir (helpers/temp-dir-as-string)
        repo-config [repo]]
    (with-test-logging
      (helpers/init-bare-repo! (fs/file git-dir))
      (let [local-temp-file (str local-repo-dir "/temp-test")
            working-temp-file (str working-dir "/temp-test")
            local-temp-file-2 (str local-repo-dir "/temp-test-2")
            working-temp-file-2 (str working-dir "/temp-test-2")
            temp-file-1-content "temp file 1 content"
            temp-file-2-content "temp file 2 content"
            local-repo (helpers/init-repo! (fs/file local-repo-dir))]

        (fs/touch local-temp-file)
        (spit local-temp-file temp-file-1-content)
        (fs/touch local-temp-file-2)
        (spit local-temp-file-2 temp-file-2-content)
        (jgit-utils/add-and-commit local-repo "a test commit" helpers/test-identity)
        (jgit-utils/push local-repo git-dir)

        (testing "working dir should not have test file"
          (is (fs/exists? local-temp-file))
          (is (fs/exists? local-temp-file-2))
          (is (= temp-file-1-content (slurp local-temp-file)))
          (is (= temp-file-2-content (slurp local-temp-file-2)))
          (is (not (fs/exists? working-temp-file)))
          (is (not (fs/exists? working-temp-file-2))))

        (testing (str "sync-working-dir! successfully instantiates the contents of "
                      "a bare repo into a working directory")
          (sync-working-dir! client-data-dir repo-config repo working-dir)
          (is (logged? #"Syncing working directory at.*for repo.*"))
          (is (fs/exists? working-temp-file))
          (is (fs/exists? working-temp-file-2))
          (is (= temp-file-1-content (slurp working-temp-file)))
          (is (= temp-file-2-content (slurp working-temp-file-2))))

        (testing (str "sync-working-dir! should reflect changes made in the "
                      "bare repo when the working dir was previously instantiated")

          (fs/delete local-temp-file-2)
          (jgit-utils/add-and-commit
            local-repo "a second test commit" helpers/test-identity)
          (jgit-utils/push local-repo git-dir)

          (testing "working dir should still contain the deleted test file"
            (is (fs/exists? working-temp-file-2))
            (is (not (fs/exists? local-temp-file-2))))

          (testing "contents of working-dir should be overridden when synced"
            (sync-working-dir! client-data-dir repo-config repo working-dir)
            (is (fs/exists? working-temp-file))
            (is (not (fs/exists? working-temp-file-2)))
            (is (= temp-file-1-content (slurp working-temp-file)))))

        (testing (str "sync-working-dir! should throw an exception if the "
                      "desired repo doesn't exist")
          (is (thrown? IllegalArgumentException
                       (sync-working-dir! client-data-dir repo-config "fake" working-dir))))

        (testing (str "sync-working-dir! should throw an exception if the "
                      "desired working dir doesn't exist")
          (let [fake-dir (fs/temp-name "test")]
            (is (not (fs/exists? fake-dir)))
            (is (thrown-with-msg?
                  IllegalStateException
                  #"Directory test.*must exist on disk to be synced as a working directory"
                  (sync-working-dir! client-data-dir repo-config repo fake-dir)))))))))
