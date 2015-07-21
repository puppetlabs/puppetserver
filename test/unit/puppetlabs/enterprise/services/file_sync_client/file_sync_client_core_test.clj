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

(deftest apply-updates-to-repo-test
  (let [repo-name "apply-updates-test"
        server-repo-url (str helpers/server-repo-url "/" repo-name)
        client-repo-path (ks/temp-file-name repo-name)
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

(deftest process-submodules-for-updates-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        storage-data-dir (storage-core/path-to-data-dir root-data-dir)
        client-data-dir (path-to-data-dir root-data-dir)
        server-repo "process-repos-test"
        submodules-root (str client-data-dir "/" server-repo)
        client (sync/create-client {})
        submodules-working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodule-1 "submodule-1"
        submodule-2 "submodule-2"
        submodule-1-dir (common/submodule-bare-repo storage-data-dir server-repo submodule-1)
        submodule-2-dir (common/submodule-bare-repo storage-data-dir server-repo submodule-2)
        dummy-repo (.getRepository (helpers/init-bare-repo! (ks/temp-dir)))]
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        root-data-dir
        {(keyword server-repo) {:working-dir (helpers/temp-dir-as-string)
                                :submodules-dir submodules-dir
                                :submodules-working-dir submodules-working-dir}})

      ;; Create the submodules and add commits into them
      (ks/mkdirs! (fs/file submodules-working-dir submodule-1))
      (ks/mkdirs! (fs/file submodules-working-dir submodule-2))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-1 "test.txt"))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule-2 "test.txt"))
      (http-client/post helpers/publish-url)

      (testing "submodules are successfully synced"
        (let [latest-commits (get-latest-commits-from-server client
                               (str (helpers/base-url false)
                                 helpers/default-api-path-prefix
                                 "/v1")
                               {:status :created
                                :status-data {:last_check_in nil
                                              :last_successful_sync_time nil}})
              latest-commit (get latest-commits (keyword server-repo))
              submodules-status (process-submodules-for-updates
                                  (str (helpers/base-url false)
                                    helpers/default-repo-path-prefix
                                    "/"
                                    server-repo)
                                  (:submodules latest-commit)
                                  submodules-root
                                  dummy-repo)
              submodule-1-status (get submodules-status
                                   (str submodules-dir "/" submodule-1))
              submodule-2-status (get submodules-status
                                   (str submodules-dir "/" submodule-2))]

          (testing "submodule-1 was successfuly synced with the storage service"
            (is (fs/exists? (common/bare-repo submodules-root submodule-1)))
            (is (= :synced (:status submodule-1-status)))
            (is (= (jgit-utils/head-rev-id-from-git-dir submodule-1-dir)
                  (:latest-commit submodule-1-status))))

          (testing "submodule-2 was successfully synced with the storage service"
            (is (fs/exists? (common/bare-repo submodules-root submodule-2)))
            (is (= :synced (:status submodule-2-status)))
            (is (= (jgit-utils/head-rev-id-from-git-dir submodule-2-dir)
                  (:latest-commit submodule-2-status))))))

      (testing "Repo config updated with correct submodule URLs"
        (let [ submodule-1-client-dir (common/submodule-bare-repo client-data-dir
                                        server-repo submodule-1)
              submodule-2-client-dir (common/submodule-bare-repo client-data-dir
                                       server-repo submodule-2)]
          (testing "Submodule-1's URL set to locally synced bare repo"
            (is (= (str submodule-1-client-dir)
                  (.getString
                    (.getConfig dummy-repo)
                    "submodule"
                    (str submodules-dir "/" submodule-1)
                    "url"))))
          (testing "Submodule-2's URL set to locally synced bare repo"
            (is (= (str submodule-2-client-dir)
                  (.getString
                    (.getConfig dummy-repo)
                    "submodule"
                    (str submodules-dir "/" submodule-2)
                    "url")))))))))

(defn process-repos
  [repos data-dir]
  (let [latest-commits-url (str (helpers/base-url)
                             helpers/default-api-path-prefix
                             "/v1"
                             common/latest-commits-sub-path)
        body (-> latest-commits-url
               (http-client/post {:as :text})
               get-body-from-latest-commits-payload)]
    (process-repos-for-updates*
      repos
      (str (helpers/base-url) helpers/default-repo-path-prefix)
      body
      data-dir)))

(deftest process-repos-for-updates*-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        storage-data-dir (storage-core/path-to-data-dir root-data-dir)
        client-data-dir (path-to-data-dir root-data-dir)
        server-repo :process-repos-test
        error-repo  :process-repos-error
        nonexistent-repo :process-repos-test-nonexistent
        client-target-repo-on-server (common/bare-repo client-data-dir server-repo)
        client-target-repo-nonexistent (common/bare-repo client-data-dir nonexistent-repo)
        client-target-repo-error (common/bare-repo client-data-dir error-repo)
        submodules-working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodule "submodule"]
    (helpers/with-bootstrapped-storage-service
      app
      (helpers/storage-service-config
        root-data-dir
        {server-repo {:working-dir (helpers/temp-dir-as-string)
                      :submodules-dir submodules-dir
                      :submodules-working-dir submodules-working-dir}
         error-repo {:working-dir (helpers/temp-dir-as-string)}})
      (ks/mkdirs! (fs/file submodules-working-dir submodule))
      (http-client/post helpers/publish-url)
      (fs/delete-dir client-target-repo-on-server)
      (fs/delete-dir client-target-repo-nonexistent)
      (fs/delete-dir client-target-repo-error)
      (fs/delete-dir (common/bare-repo storage-data-dir error-repo))

      (with-test-logging
        (let [state (process-repos [server-repo error-repo nonexistent-repo]
                                   client-data-dir)]

          (testing "process-repos-for-updates returns correct state info"
            (is (= :synced (get-in state [server-repo :status])))
            (is (not (nil? (get-in state [server-repo :latest-commit]))))
            (is (not (nil? (get-in state [server-repo :submodules]))))
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
          (is (fs/exists? (common/submodule-bare-repo client-data-dir server-repo submodule))))

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

(deftest sync-working-dir-test
  (let [client-data-dir (helpers/temp-dir-as-string)
        repo :test-repo
        git-dir (common/bare-repo client-data-dir repo)
        working-dir (helpers/temp-dir-as-string)
        local-repo-dir (helpers/temp-dir-as-string)]
    (with-test-logging
      (helpers/init-bare-repo! git-dir)
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
        (jgit-utils/add-and-commit local-repo "a test commit" helpers/test-person-ident)
        (jgit-utils/push local-repo (str git-dir))

        (testing "working dir should not have test file"
          (is (fs/exists? local-temp-file))
          (is (fs/exists? local-temp-file-2))
          (is (= temp-file-1-content (slurp local-temp-file)))
          (is (= temp-file-2-content (slurp local-temp-file-2)))
          (is (not (fs/exists? working-temp-file)))
          (is (not (fs/exists? working-temp-file-2))))

        (testing (str "sync-working-dir! successfully instantiates the contents of "
                      "a bare repo into a working directory")
          (sync-working-dir! client-data-dir repo working-dir)
          (is (logged? #"Syncing working directory at.*for repo.*"))
          (is (fs/exists? working-temp-file))
          (is (fs/exists? working-temp-file-2))
          (is (= temp-file-1-content (slurp working-temp-file)))
          (is (= temp-file-2-content (slurp working-temp-file-2))))

        (testing (str "sync-working-dir! should reflect changes made in the "
                      "bare repo when the working dir was previously instantiated")

          (fs/delete local-temp-file-2)
          (jgit-utils/add-and-commit
            local-repo "a second test commit" helpers/test-person-ident)
          (jgit-utils/push local-repo (str git-dir))

          (testing "working dir should still contain the deleted test file"
            (is (fs/exists? working-temp-file-2))
            (is (not (fs/exists? local-temp-file-2))))

          (testing "contents of working-dir should be overridden when synced"
            (sync-working-dir! client-data-dir repo working-dir)
            (is (fs/exists? working-temp-file))
            (is (not (fs/exists? working-temp-file-2)))
            (is (= temp-file-1-content (slurp working-temp-file)))))

        (testing (str "sync-working-dir! should throw an exception if the "
                      "desired repo doesn't exist")
          (is (thrown? IllegalArgumentException
                       (sync-working-dir! client-data-dir :fake working-dir))))

        (testing (str "sync-working-dir! should create the desired working dir "
                      "if it doesn't exist")
          (let [test-dir (ks/temp-file-name "test")]
            (is (not (fs/exists? test-dir)))
            (sync-working-dir! client-data-dir repo test-dir)
            (is (fs/exists? test-dir))))))))

(deftest process-callbacks-test
  (let [atom-start-value {:count 0}
        callback-result-unified (atom atom-start-value)
        callback-result-repo-1 (atom atom-start-value)
        callback-result-repo-2 (atom atom-start-value)
        generate-callback-fn (fn [atom]
                               (fn [repos-status]
                                 (swap! atom #(assoc % :status repos-status
                                                       :count (+ 1 (:count (deref atom)))))))
        repo1 :repo1
        repo2 :repo2
        repo-1-callback (generate-callback-fn callback-result-repo-1)
        repo-2-callback (generate-callback-fn callback-result-repo-2)
        unified-callback (generate-callback-fn callback-result-unified)
        callbacks {repo1 #{repo-1-callback unified-callback}
                   repo2 #{repo-2-callback unified-callback}}
        call-callback (partial process-callbacks! callbacks)
        status {repo1 {:status :synced
                       :latest-commit nil}
                repo2 {:status :synced
                       :latest-commit nil}}]
    (testing "relevant callbacks called when all registered repos are synced"
      (call-callback status)
      (testing "callback for first repo is called once"
        (let [callback-result (deref callback-result-repo-1)]
          (is (= (select-keys status [repo1]) (:status callback-result)))
          (is (= 1 (:count callback-result)))))
      (testing "callback for second repo is called once"
        (let [callback-result (deref callback-result-repo-2)]
          (is (= (select-keys status [repo2]) (:status callback-result)))
          (is (= 1 (:count callback-result)))))
      (testing "unified callback is called once"
        (let [callback-result (deref callback-result-unified)]
          (is (= status (:status callback-result)))
          (is (= 1 (:count callback-result))))))

    (testing "callback called when only one registered repo is synced"
      (reset! callback-result-unified atom-start-value)
      (reset! callback-result-repo-1 atom-start-value)
      (reset! callback-result-repo-2 atom-start-value)
      (let [repos-status (assoc-in status [repo1 :status] :unchanged)]
        (call-callback repos-status)
        (testing "callback for first repo is not called"
          (is (= {:count 0} (deref callback-result-repo-1))))
        (testing "callback for second repo is called once"
          (let [callback-result (deref callback-result-repo-2)]
            (is (= (select-keys repos-status [repo2]) (:status callback-result)))
            (is (= 1 (:count callback-result)))))
        (testing "callback for unified repo is called once with correct status"
          (let [callback-result (deref callback-result-unified)]
            (is (= repos-status (:status callback-result)))
            (is (= 1 (:count callback-result)))))))

    (testing "no callbacks called when registered repos are not synced"
      (reset! callback-result-unified atom-start-value)
      (reset! callback-result-repo-1 atom-start-value)
      (reset! callback-result-repo-2 atom-start-value)
      (let [unchanged-status {:status :unchanged
                              :latest-commit nil}
            repos-status (assoc status repo1 unchanged-status repo2 unchanged-status)]
        (call-callback repos-status)
        (is (= atom-start-value (deref callback-result-repo-1)))
        (is (= atom-start-value (deref callback-result-repo-2)))
        (is (= atom-start-value (deref callback-result-unified)))))))
