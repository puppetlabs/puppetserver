(ns puppetlabs.enterprise.file-sync-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as file-sync-client-core]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service :as file-sync-client-service]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as file-sync-storage-core]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service :as file-sync-storage-service]
            [puppetlabs.enterprise.services.protocols.file-sync-client :as client-protocol]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as scheduler-service]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty-service]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.services.status.status-service :as status-service]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs])
  (:import (java.net ConnectException)
           (org.eclipse.jgit.diff DiffEntry$ChangeType)))

(use-fixtures :once schema-test/validate-schemas)

(deftest ^:integration ssl-integration-test
  (testing "everything works properly when using ssl"
    (let [repo "ssl-integration-test"
          root-data-dir (helpers/temp-dir-as-string)
          storage-data-dir (file-sync-storage-core/path-to-data-dir root-data-dir)
          client-data-dir (file-sync-client-core/path-to-data-dir root-data-dir)
          client-repo-dir (common/bare-repo client-data-dir repo)]
      (with-test-logging
        (bootstrap/with-app-with-config
          app
          [jetty-service/jetty9-service
           file-sync-storage-service/file-sync-storage-service
           webrouting-service/webrouting-service
           file-sync-client-service/file-sync-client-service
           status-service/status-service
           scheduler-service/scheduler-service]
          (helpers/file-sync-config
            root-data-dir
            {(keyword repo) {:working-dir (helpers/temp-dir-as-string)}}
            true)

          (let [local-repo-dir (helpers/clone-and-push-test-commit! repo storage-data-dir)
                sync-agent (helpers/get-sync-agent app)]

            (testing "client is able to successfully sync with storage service"
              (let [new-state (helpers/wait-for-new-state sync-agent)]
                (is (= :successful (:status new-state)))))

            (testing "client repo revision matches server local repo"
              ;; The client syncs with the storage service, and the
              ;; storage service repo was updated from the working copy
              ;; of the local repo. Thus, this test assures that the whole
              ;; process was successful.
              (is (= (jgit-utils/head-rev-id-from-git-dir client-repo-dir)
                     (jgit-utils/head-rev-id-from-working-tree local-repo-dir))))))))))

(deftest ^:integration network-partition-tolerance-test
  (testing "file sync client recovers after storage service becomes temporarily inaccessible"
    (let [repo "network-partition-test"
          root-data-dir (helpers/temp-dir-as-string)
          storage-data-dir (file-sync-storage-core/path-to-data-dir root-data-dir)
          storage-app (tk-app/check-for-errors!
                        (tk/boot-services-with-config
                          [jetty-service/jetty9-service
                           file-sync-storage-service/file-sync-storage-service
                           status-service/status-service
                           webrouting-service/webrouting-service]
                          (helpers/storage-service-config
                            root-data-dir
                            {(keyword repo) {:working-dir (helpers/temp-dir-as-string)}})))]
      (try
        (let [client-data-dir (file-sync-client-core/path-to-data-dir root-data-dir)
              client-repo-dir (common/bare-repo client-data-dir repo)
              ;; clone the repo from the storage service, create and commit a new
              ;; file, and push it back up to the server. Returns the path to the
              ;; locally cloned repo so that we can push additional files to it later.
              local-repo-dir (helpers/clone-and-push-test-commit! repo storage-data-dir)]

          (testing "file sync storage service is running"
            ;; Ensure that a commit pushed to the server is reflected by the
            ;; latest-commits endpoint
            (is (= (helpers/get-latest-commits-for-repo repo)
                   (jgit-utils/head-rev-id-from-working-tree local-repo-dir))))

          (bootstrap/with-app-with-config
            app
            helpers/client-service-and-deps
            (helpers/client-service-config
              root-data-dir
              false)

            (let [sync-agent (helpers/get-sync-agent app)]

              (testing "file sync client service is running"
                ;; Test the result of the periodic sync by checking the SHA for
                ;; the latest commit returned from the storage service's
                ;; latest-commits endpoint against the client's latest commit.
                ;; Make the agent running the periodic sync process 'deliver'
                ;; our promise once it's done.
                ;; Block until we are sure that the periodic sync process
                ;; has completed.
                (let [new-state (helpers/wait-for-new-state sync-agent)]
                  (is (= :successful (:status new-state))))
                (is (= (helpers/get-latest-commits-for-repo repo)
                       (jgit-utils/head-rev-id-from-git-dir client-repo-dir))))

              (testing "kill storage service and verify client has errors"
                ;; Stop the storage service - the next time the sync runs, it
                ;; should fail.  Use 'with-test-logging' just to prevent the
                ;; error from being logged.
                (with-test-logging
                  (tk-app/stop storage-app)

                  ;; Wait until the client polls again, and this time an error
                  ;; should occur because it can't connect to the server.  This is
                  ;; accomplished by adding a watch on the
                  ;; agent which simply delivers a promise.  This test then
                  ;; deref's that promise, thereby blocking until the error occurs.
                  (let [new-state (helpers/wait-for-new-state sync-agent)]
                    ;; The sync process failed, and we were delivered an error.
                    ;; Verify that it is what it should be.
                    (is (= :failed (:status new-state)))
                    (let [error (:error new-state)]
                      (testing "the delivered error describes a network connectivity problem"
                        (is (map? error))
                        (let [cause (:cause error)]
                          (is (instance? ConnectException cause))
                          (is (re-matches
                                #"Connection refused.*"
                                (.getMessage cause)))))))))

              (testing "start storage service again"
                (tk-app/start storage-app)
                (is (= 200 (:status (helpers/latest-commits-response))))

                ;; push a new commit up to the storage service
                (helpers/push-test-commit! local-repo-dir)

                ;; At this point, the storage service should have one more commit than
                ;; the client, since the client has not yet had time to sync with it,
                ;; so the SHA returned from latest-commits and the revision ID for the
                ;; client should not be the same
                (is (not= (helpers/get-latest-commits-for-repo repo)
                          (jgit-utils/head-rev-id-from-git-dir client-repo-dir))))

              (testing "verify client recovers"
                ;; Same deal as above - wait until the client polls again, then
                ;; check that the client has been synced to have the same latest
                ;; commit as the storage service
                (let [p (promise)]
                  ;; Make the agent running the periodic sync process 'deliver'
                  ;; our promise once it's done.
                  (helpers/add-watch-and-deliver-new-state sync-agent p)
                  ;; Block until we are sure that the periodic sync process
                  ;; has completed.
                  (let [new-state (deref p)]
                    (is (= :successful (:status new-state))))
                  (is (= (helpers/get-latest-commits-for-repo repo)
                         (jgit-utils/head-rev-id-from-git-dir client-repo-dir))))))))
        (finally (tk-app/stop storage-app))))))

(deftest ^:integration server-side-corruption-test
  (let [root-data-dir (helpers/temp-dir-as-string)
        storage-data-dir (file-sync-storage-core/path-to-data-dir root-data-dir)
        client-data-dir (file-sync-client-core/path-to-data-dir root-data-dir)
        client-dir-repo-1 (common/bare-repo client-data-dir :repo1)
        client-dir-repo-2 (common/bare-repo client-data-dir :repo2)]
    ;; This is used to silence the error logged when the server-side repo is
    ;; corrupted, but unfortunately, it doesn't seem to actually allow that
    ;; message to be matched in a (is (thrown? ...) kind of assertion.
    (with-test-logging
      (bootstrap/with-app-with-config
        app
        helpers/file-sync-services-and-deps
        (helpers/file-sync-config
          root-data-dir
          {:repo1 {:working-dir (helpers/temp-dir-as-string)}
           :repo2 {:working-dir (helpers/temp-dir-as-string)}})
        (let [local-dir-repo-1 (helpers/clone-and-push-test-commit!
                                 (name :repo1) storage-data-dir)
              local-dir-repo-2 (helpers/clone-and-push-test-commit!
                                 (name :repo2) storage-data-dir)
              sync-agent (helpers/get-sync-agent app)]

          (testing "File sync client service is running"
            (let [new-state (helpers/wait-for-new-state sync-agent)]
              (is (= :successful (:status new-state))))
            (is (= (helpers/get-latest-commits-for-repo :repo1)
                   (jgit-utils/head-rev-id-from-git-dir client-dir-repo-1)))
            (is (= (helpers/get-latest-commits-for-repo :repo2)
                   (jgit-utils/head-rev-id-from-git-dir client-dir-repo-2))))

          (testing (str "Client-side repo recovers after server-side"
                        " repo becomes corrupt")
            (let [corrupt-repo-path (helpers/temp-dir-as-string)
                  original-repo-path (common/bare-repo storage-data-dir :repo1)]
              (helpers/push-test-commit! local-dir-repo-1)
              (helpers/push-test-commit! local-dir-repo-2)

              ;; "Corrupt" the server-side repo by moving it to a different location.
              ;; Note that there is a possibility that between pushing the new
              ;; commits and renaming this directory, a sync could have
              ;; happened. This should not affect the outcome of this test.
              (fs/rename original-repo-path corrupt-repo-path)
              (testing (str "Corruption of one repo does not affect "
                            " syncing of other repos")
                (let [new-state (helpers/wait-for-new-state sync-agent)]
                  (is (= :partial-success (:status new-state)))
                  (testing "Corrupted repo sync state is failed"
                    ;; This should be nil, as the directory that is
                    ;; supposed to contain repo1 no longer exists
                    (is (nil? (helpers/get-latest-commits-for-repo :repo1)))
                    (is (= :failed (get-in new-state [:repos :repo1 :status]))))

                  (testing "Non-corrupted repo sync state is not failed"
                    ;; Depending on whether there was a sync between pushing
                    ;; to repo2 and when we add the watch, repo2 could have a
                    ;; status of "synced" or a status of "unchanged"
                    (is (contains? #{:synced :unchanged}
                          (get-in new-state [:repos :repo2 :status])))
                    (is (= (get-in new-state [:repos :repo2 :latest_commit])
                           (helpers/get-latest-commits-for-repo :repo2)
                           (jgit-utils/head-rev-id-from-git-dir
                             client-dir-repo-2)))))
                ;; "Restore" the server-side repo by moving it back to
                ;; its original location
                (fs/rename corrupt-repo-path original-repo-path)
                (helpers/push-test-commit! local-dir-repo-1)
                (helpers/push-test-commit! local-dir-repo-2)
                (testing (str "Client recovers when the server-side "
                              "repo is fixed")
                  (let [new-state (helpers/wait-for-new-state sync-agent)]
                    (is (= :successful (:status new-state))))
                  (testing (str "All repos including the previously "
                                "corrupted ones are synced")
                    (is (= (helpers/get-latest-commits-for-repo :repo1)
                           (jgit-utils/head-rev-id-from-git-dir client-dir-repo-1)))
                    (is (= (helpers/get-latest-commits-for-repo :repo2)
                           (jgit-utils/head-rev-id-from-git-dir client-dir-repo-2)))))))))))))

(defprotocol CallbackService)

;; Used to register a callback, since they can only be
;; registered in the init phase
(tk/defservice callback-service
  CallbackService
  [[:FileSyncClientService register-callback!]]
  (init [this context]
    (let [atom-1 (atom nil)
          atom-2 (atom nil)
          atom-3 (atom {:count 0})
          reused-fn (fn [repo-status]
                      (swap! atom-3
                        assoc :status repo-status :count (+ 1 (:count (deref atom-3)))))]
      (register-callback! #{:repo :repo2}
                          (fn [repo-status]
                            (reset! atom-1
                                   (keys repo-status))))
      (register-callback! #{:repo}
                          (fn [repo-status]
                            (reset! atom-2
                                   (keys repo-status))))
      (register-callback! #{:repo} reused-fn)
      (register-callback! #{:repo2} reused-fn)
      (assoc context :atom-1 atom-1 :atom-2 atom-2 :atom-3 atom-3))))

(deftest ^:integration callback-registration-test
  (testing "callback functions can be registered with the client service"
    (let [repo :repo
          repo-2 :repo2
          root-data-dir (helpers/temp-dir-as-string)]
      (with-test-logging
        (bootstrap/with-app-with-config
          app
          [jetty-service/jetty9-service
           file-sync-storage-service/file-sync-storage-service
           webrouting-service/webrouting-service
           file-sync-client-service/file-sync-client-service
           scheduler-service/scheduler-service
           status-service/status-service
           callback-service]
          (helpers/file-sync-config
            root-data-dir
            {repo {:working-dir (helpers/temp-dir-as-string)}
             repo-2 {:working-dir (helpers/temp-dir-as-string)}}
            false)

          (let [sync-agent (helpers/get-sync-agent app)
                svc (tk-app/get-service app :CallbackService)
                context (tk-services/service-context svc)
                atom-1 (:atom-1 context)
                atom-2 (:atom-2 context)
                atom-3 (:atom-3 context)]

            (testing "file sync client service is running"
              (let [new-state (helpers/wait-for-new-state sync-agent)]
                (is (= :successful (:status new-state)))))

            (testing "callbacks called when all registered repos are synced"
              (is (= [repo repo-2] (deref atom-1)))
              (is (= [repo] (deref atom-2))))

            (testing "single callback can be registered multiple times"
              (let [result (deref atom-3)
                    repo-status (get-in result [:status repo])
                    repo2-status (get-in result [:status repo-2])]
                (is (= :synced (:status repo-status)))
                (is (= :synced (:status repo2-status)))
                (is (= 1 (:count result)))))


            (reset! atom-1 nil)
            (reset! atom-2 nil)
            (reset! atom-3 {:count 0})
            (helpers/clone-and-push-test-commit! (name repo) (str root-data-dir "/storage"))
            (let [new-state (helpers/wait-for-new-state sync-agent)]
              (is (= :successful (:status new-state))))

            (testing "callbacks called when only some registered repos are synced"
              (is (= [repo repo-2] (deref atom-1)))
              (is (= [repo] (deref atom-2)))

              (let [result (deref atom-3)
                    repo-status (get-in result [:status repo])
                    repo2-status (get-in result [:status repo-2])]
                (is (= :synced (:status repo-status)))
                (is (= :unchanged (:status repo2-status)))
                (is (= 1 (:count result)))))

            (reset! atom-1 nil)
            (reset! atom-2 nil)
            (reset! atom-3 {:count 0})

            (let [new-state (helpers/wait-for-new-state sync-agent)]
              (is (= :successful (:status new-state))))

            (testing "callbacks not called when no registered repos are synced"
              (is (nil? (deref atom-1)))
              (is (nil? (deref atom-2)))
              (is (= 0 (:count (deref atom-3)))))))))))

(deftest ^:integration nested-git-directory-test
  (testing "file sync works with nested .git directories"
    (let [repo-name "parent-repo"
          working-dir (helpers/temp-dir-as-string)
          submodules-working-dir (helpers/temp-dir-as-string)
          submodules-dir-name "environments"
          submodule "production"
          nested-dir "module-a"
          nested-submodule-dir "module-b"
          test-file-name "test.txt"
          root-data-dir (helpers/temp-dir-as-string)]
      (with-test-logging
        (bootstrap/with-app-with-config
          app
          [jetty-service/jetty9-service
           file-sync-storage-service/file-sync-storage-service
           file-sync-client-service/file-sync-client-service
           webrouting-service/webrouting-service
           scheduler-service/scheduler-service
           status-service/status-service]
          (helpers/file-sync-config
            root-data-dir
            {(keyword repo-name) {:working-dir working-dir
                                  :submodules-working-dir submodules-working-dir
                                  :submodules-dir submodules-dir-name}})

          ; add test file to parent repo
          (spit (fs/file working-dir test-file-name) "test file in the parent repo")

          ; create directory for the submodule and add test file
          (ks/mkdirs! (fs/file submodules-working-dir submodule))
          (spit (fs/file submodules-working-dir submodule test-file-name)
            "test file in the submodule")

          ; first publish, to get parent repo's and submodule repo's initial
          ; states onto the storage service, so that we have a diff to test
          (let [response (http-client/post helpers/publish-url)]
            (is (= 200 (:status response))))

          ; create a directory with a nested .git directory in the parent repo
          (let [nested-dir-path (fs/file working-dir nested-dir)
                nested-git (helpers/init-repo! nested-dir-path)]
            (ks/mkdirs! nested-dir-path)
            (spit (fs/file nested-dir-path test-file-name)
              "test file in the nested directory in the parent repo")
            (jgit-utils/add-and-commit nested-git
              "Commit nested git repo" helpers/test-person-ident))

          ; delete file from submodule
          (fs/delete (fs/file submodules-working-dir submodule test-file-name))

          ; create a directory with a nested .git directory in the submodule
          (let [nested-submodule-path (fs/file submodules-working-dir submodule nested-submodule-dir)
                nested-submodule-git (helpers/init-repo! nested-submodule-path)]
            (ks/mkdirs! nested-submodule-path)
            (spit (fs/file nested-submodule-path test-file-name)
              "test file in the nested directory in the submodule")
            (jgit-utils/add-and-commit nested-submodule-git
              "Commit submodule nested git repo" helpers/test-person-ident))

          (let [response (http-client/post helpers/publish-url)]
            (is (= 200 (:status response))))

          (testing "storage service stores nested git directories correctly"
            (testing "submodule has correct diff"
              (let [repo (jgit-utils/get-repository-from-git-dir
                           (common/submodule-bare-repo (str root-data-dir "/storage") repo-name submodule))
                    diffs (helpers/get-latest-commit-diff repo)]

                (is (= #{{:old-path "/dev/null"
                           :new-path (format "%s/%s" nested-submodule-dir test-file-name)
                           :change-type (DiffEntry$ChangeType/ADD)}
                         {:old-path test-file-name
                          :new-path "/dev/null"
                          :change-type (DiffEntry$ChangeType/DELETE)}}
                      (set (map (fn [x] {:old-path (.getOldPath x)
                                         :new-path (.getNewPath x)
                                         :change-type (.getChangeType x)})
                             diffs))))))

            (testing "parent repo has correct diff"
              (let [repo (jgit-utils/get-repository-from-git-dir
                           (common/bare-repo (str root-data-dir "/storage") repo-name))
                    diffs (helpers/get-latest-commit-diff repo)]

                (is (= #{{:old-path (format "%s/%s" submodules-dir-name submodule)
                          :new-path (format "%s/%s" submodules-dir-name submodule)
                          :new-mode "160000"
                          :change-type (DiffEntry$ChangeType/MODIFY)}
                         {:old-path "/dev/null"
                          :new-path (format "%s/%s" nested-dir test-file-name)
                          :new-mode "100644"
                          :change-type (DiffEntry$ChangeType/ADD)}}
                      (set (map (fn [x] {:old-path (.getOldPath x)
                                         :new-path (.getNewPath x)
                                         :change-type (.getChangeType x)
                                         :new-mode (.toString (.getNewMode x))})
                             diffs)))))))

          (testing "client service syncs correctly"
            (let [client-service (tk-app/get-service app :FileSyncClientService)
                  sync-agent (helpers/get-sync-agent app)
                  client-working-dir (helpers/temp-dir-as-string) ]

              (testing "client working dir starts out empty"
                (is (nil? (fs/list-dir client-working-dir))))

              (testing "client service syncs to working dir when sync function is called"
                (helpers/wait-for-new-state sync-agent)

                (client-protocol/sync-working-dir!
                  client-service
                  (keyword repo-name)
                  (str client-working-dir))

                (is (not (fs/exists?  (fs/file client-working-dir submodules-dir-name
                                        submodule test-file-name))))
                (is (= "test file in the parent repo"
                      (slurp (fs/file client-working-dir test-file-name))))
                (is (= "test file in the nested directory in the parent repo"
                      (slurp (fs/file client-working-dir nested-dir test-file-name))))
                (is (= "test file in the nested directory in the submodule"
                      (slurp (fs/file client-working-dir submodules-dir-name submodule
                               nested-submodule-dir test-file-name))))))))))))
