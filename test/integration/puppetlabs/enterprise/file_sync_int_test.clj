(ns puppetlabs.enterprise.file-sync-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
             :as file-sync-client-core]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
             :as file-sync-client-service]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service
             :as file-sync-storage-service]
            [puppetlabs.enterprise.services.scheduler.scheduler-service
             :as scheduler-service]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty-service]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs])
  (:import (java.net ConnectException)))

(use-fixtures :once schema-test/validate-schemas)

(def latest-commits-url (str helpers/server-base-url
                             helpers/default-api-path-prefix
                             "/v1"
                             common/latest-commits-sub-path))

(defn latest-commits-response
  []
  (http-client/get latest-commits-url {:as :text}))

(defn get-latest-commits
  []
  (file-sync-client-core/get-body-from-latest-commits-payload
    (latest-commits-response)))

(defn get-latest-commits-for-repo
  [repo]
  (get-in (get-latest-commits) [(keyword repo) :commit]))

(deftest ^:integration ssl-integration-test
  (testing "everything works properly when using ssl"
    (let [repo "ssl-integration-test"
          data-dir (helpers/temp-dir-as-string)
          client-repo-dir (fs/file (helpers/temp-dir-as-string) repo)]
      (with-test-logging
        (bootstrap/with-app-with-config
          app
          [jetty-service/jetty9-service
           file-sync-storage-service/file-sync-storage-service
           webrouting-service/webrouting-service
           file-sync-client-service/file-sync-client-service
           scheduler-service/scheduler-service]
          (merge (helpers/storage-service-config-with-repos
                   data-dir
                   {(keyword repo) {:working-dir (helpers/temp-dir-as-string)}}
                   true)
                 (helpers/client-service-config-with-repos
                   {(keyword repo) (.getPath client-repo-dir)}
                   true))

          (let [local-repo-dir (helpers/clone-and-push-test-commit! repo data-dir)
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
          data-dir (helpers/temp-dir-as-string)
          storage-app (tk-app/check-for-errors!
                        (tk/boot-services-with-config
                          [jetty-service/jetty9-service
                           file-sync-storage-service/file-sync-storage-service
                           webrouting-service/webrouting-service]
                          (helpers/storage-service-config-with-repos
                            data-dir
                            {(keyword repo) {:working-dir (helpers/temp-dir-as-string)}}
                            false)))]
      (try
        (let [client-repo-dir (fs/file (helpers/temp-dir-as-string) repo)
              ;; clone the repo from the storage service, create and commit a new
              ;; file, and push it back up to the server. Returns the path to the
              ;; locally cloned repo so that we can push additional files to it later.
              local-repo-dir (helpers/clone-and-push-test-commit! repo data-dir)]

          (testing "file sync storage service is running"
            ;; Ensure that a commit pushed to the server is reflected by the
            ;; latest-commits endpoint
            (is (= (get-latest-commits-for-repo repo)
                   (jgit-utils/head-rev-id-from-working-tree local-repo-dir))))

          (bootstrap/with-app-with-config
            app
            [file-sync-client-service/file-sync-client-service
             scheduler-service/scheduler-service]
            (helpers/client-service-config-with-repos
              {(keyword repo) (.getPath client-repo-dir)}
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
                (is (= (get-latest-commits-for-repo repo)
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
                (is (= 200 (:status (latest-commits-response))))

                ;; push a new commit up to the storage service
                (helpers/push-test-commit! local-repo-dir)

                ;; At this point, the storage service should have one more commit than
                ;; the client, since the client has not yet had time to sync with it,
                ;; so the SHA returned from latest-commits and the revision ID for the
                ;; client should not be the same
                (is (not= (get-latest-commits-for-repo repo)
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
                  (is (= (get-latest-commits-for-repo repo)
                         (jgit-utils/head-rev-id-from-git-dir client-repo-dir))))))))
        (finally (tk-app/stop storage-app))))))

(deftest ^:integration server-side-corruption-test
  (let [repo1 "repo1"
        repo2 "repo2"
        data-dir (helpers/temp-dir-as-string)
        client-dir-repo-1 (fs/file (helpers/temp-dir-as-string) repo1)
        client-dir-repo-2 (fs/file (helpers/temp-dir-as-string) repo2)]
    ;; This is used to silence the error logged when the server-side repo is
    ;; corrupted, but unfortunately, it doesn't seem to actually allow that
    ;; message to be matched
    (with-test-logging
      (bootstrap/with-app-with-config
        app
        [jetty-service/jetty9-service
         file-sync-storage-service/file-sync-storage-service
         webrouting-service/webrouting-service
         file-sync-client-service/file-sync-client-service
         scheduler-service/scheduler-service]
        (merge (helpers/storage-service-config-with-repos
                 data-dir
                 {(keyword repo1) {:working-dir (helpers/temp-dir-as-string)}
                  (keyword repo2) {:working-dir (helpers/temp-dir-as-string)}}
                 false)
               (helpers/client-service-config-with-repos
                 {(keyword repo1) (.getPath client-dir-repo-1)
                  (keyword repo2) (.getPath client-dir-repo-2)}
                 false))
        (let [local-dir-repo-1 (helpers/clone-and-push-test-commit! repo1 data-dir)
              local-dir-repo-2 (helpers/clone-and-push-test-commit! repo2 data-dir)
              sync-agent (helpers/get-sync-agent app)]

          (testing "file sync client service is running"
            (let [new-state (helpers/wait-for-new-state sync-agent)]
              (is (= :successful (:status new-state))))
            (is (= (get-latest-commits-for-repo repo1)
                   (jgit-utils/head-rev-id-from-git-dir client-dir-repo-1)))
            (is (= (get-latest-commits-for-repo repo2)
                   (jgit-utils/head-rev-id-from-git-dir client-dir-repo-2))))

          (testing (str "client-side repo recovers after server-side"
                        "repo becomes corrupt")
            (let [corrupt-repo-path (helpers/temp-dir-as-string)
                  original-repo-path (str data-dir "/" repo1 ".git")]
              (helpers/push-test-commit! local-dir-repo-1)
              (helpers/push-test-commit! local-dir-repo-2)
              ;; "Corrupt" the server-side repo by moving it
              ;; to a different location
              (fs/rename original-repo-path corrupt-repo-path)
              (testing (str "corruption of one repo does not affect "
                            "syncing of other repos")
                (let [p (promise)]
                  (helpers/add-watch-and-deliver-new-state sync-agent p)
                  (deref p)
                  (testing "all repos but the corrupted one are synced"
                    ;; This should be nil, as the directory that is
                    ;; supposed to contain repo1 no longer exists
                    (is (nil? (get-latest-commits-for-repo repo1)))
                    ;; The moved server-side repo should have newer
                    ;; commits than the client directory
                    ;; as it was moved before syncing happened
                    (is (not= (jgit-utils/head-rev-id-from-git-dir
                                corrupt-repo-path)
                              (jgit-utils/head-rev-id-from-git-dir
                                client-dir-repo-1)))
                    (is (= (get-latest-commits-for-repo repo2)
                           (jgit-utils/head-rev-id-from-git-dir
                             client-dir-repo-2)))))
                ;; "Restore" the server-side repo by moving it back to
                ;; its original location
                (fs/rename corrupt-repo-path original-repo-path)
                (helpers/push-test-commit! local-dir-repo-1)
                (helpers/push-test-commit! local-dir-repo-2)
                (testing (str "client recovers when the server-side "
                              "repo is fixed")
                  (let [new-state (helpers/wait-for-new-state sync-agent)]
                    (is (= :successful (:status new-state))))
                  (testing (str "all repos including the previously "
                                "corrupted ones are synced")
                    (is (= (get-latest-commits-for-repo repo1)
                           (jgit-utils/head-rev-id-from-git-dir client-dir-repo-1)))
                    (is (= (get-latest-commits-for-repo repo2)
                           (jgit-utils/head-rev-id-from-git-dir client-dir-repo-2)))))))))))))

(defprotocol CallbackService)

;; Used to register a callback, since they can only be
;; registered in the init phase
(tk/defservice callback-service
  CallbackService
  [[:FileSyncClientService register-callback!]]
  (init [this context]
    (let [repo-atom (atom {})]
      (register-callback! :repo
                         (fn [repo-id repo-status]
                           (swap! repo-atom
                                  #(assoc % :repo-id repo-id
                                            :repo-status repo-status))))
      (assoc context :atom repo-atom))))

(deftest ^:integration callback-registration-test
  (testing "callback functions can be registered with the client service"
    (let [repo "repo"
          data-dir (helpers/temp-dir-as-string)
          client-repo-dir (str (helpers/temp-dir-as-string) "/" repo)]
      (with-test-logging
        (bootstrap/with-app-with-config
          app
          [jetty-service/jetty9-service
           file-sync-storage-service/file-sync-storage-service
           webrouting-service/webrouting-service
           file-sync-client-service/file-sync-client-service
           scheduler-service/scheduler-service
           callback-service]
          (merge (helpers/storage-service-config-with-repos
                   data-dir
                   {(keyword repo) {:working-dir repo}}
                   false)
                 (helpers/client-service-config-with-repos
                   {(keyword repo) (str client-repo-dir)}
                   false))
          (let [local-repo-dir (helpers/clone-and-push-test-commit! repo data-dir)
                sync-agent (helpers/get-sync-agent app)
                svc (tk-app/get-service app :CallbackService)
                repo-atom (:atom (tk-services/service-context svc))]

            ;; This test does not test that the callback function is called when
            ;; a clone is performed, as that is handled in the process-repos-for-updates-test
            ;; in the file-sync-client-core-test namespace.
            (testing "file sync client service is running"
              (let [new-state (helpers/wait-for-new-state sync-agent)]
                (is (= :successful (:status new-state))))
              (is (= (get-latest-commits-for-repo repo)
                     (jgit-utils/head-rev-id-from-git-dir client-repo-dir))))

            (testing "registered callback is not called when no fetch or clone is performed"
              (let [p (promise)]
                (reset! repo-atom {})
                (helpers/add-watch-and-deliver-new-state sync-agent p)
                (let [new-state (deref p)]
                  (is (= :successful (:status new-state))))
                (is (= {} @repo-atom))))

            (testing "registered callback is called when a fetch is performed"
              (helpers/push-test-commit! local-repo-dir)
              (reset! repo-atom {})
              (let [p (promise)]
                (helpers/add-watch-and-deliver-new-state sync-agent p)
                (let [new-state (deref p)
                      repo-state @repo-atom]
                  (is (= :successful (:status new-state)))
                  (is (= :repo (:repo-id repo-state)))
                  (is (= :synced (get-in repo-state [:repo-status :status])))
                  (is (= (get-latest-commits-for-repo repo)
                         (get-in repo-state [:repo-status :latest-commit]))))))))))))
