(ns puppetlabs.enterprise.file-sync-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.enterprise.jgit-client-test-helpers :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
             :as file-sync-client-core]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
             :as file-sync-client-service]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service
             :as file-sync-storage-service]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.app :as tka]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty-service]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]))

(def latest-commits-url (str helpers/server-base-url
                             common/default-api-path-prefix
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
  (get (get-latest-commits) repo))

(deftest ^:integration network-partition-tolerance-test
  (testing "file sync client recovers after storage service becomes temporarily inaccessible"
    (logging/with-test-logging
      (let [repo "network-partition-test.git"
            client-repo-dir (str (helpers/temp-dir-as-string) "/" repo)
            storage-app (tk/boot-services-with-config
                          [jetty-service/jetty9-service
                           file-sync-storage-service/file-sync-storage-service]
                          (helpers/jgit-plaintext-config-with-repos
                            (helpers/temp-dir-as-string)
                            [{:sub-path repo}]))

            ;; clone the repo from the storage service, create and commit a new
            ;; file, and push it back up to the server. Returns the path to the
            ;; locally cloned repo so that we can push additional files to it later.
            local-repo-dir (helpers/clone-repo-and-push-test-files repo)]

        (testing "file sync storage service is running"
          ;; Ensure that a commit pushed to the server is reflected by the
          ;; latest-commits endpoint
          (is (= (get-latest-commits-for-repo repo)
                 (jgit-client/head-rev-id local-repo-dir))))

        (bootstrap/with-app-with-config
          app
          [file-sync-client-service/file-sync-client-service]
          {:file-sync-client {:server-url helpers/server-base-url
                              :poll-interval 2
                              :repos [{:name repo
                                       :target-dir client-repo-dir}]}}

          (testing "file sync client service is running"
            ;; wait the 2 second polling interval for the client to sync from
            ;; the storage service, then test this by checking the SHA for the
            ;; latest commit returned from the storage service's latest-commits
            ;; endpoint against the client's latest commit
            (Thread/sleep 2000)
            (is (= (get-latest-commits-for-repo repo)
                   (jgit-client/head-rev-id client-repo-dir))))

          (testing "kill storage service and verify client has errors"
            (tka/stop storage-app)

            ;; within 2 seconds the client should poll again, and this time it
            ;; should log an error because it can't connect to the server
            (Thread/sleep 2000)
            (is (logged? #"^File sync failure.\s*Cause:.*" :error)))

          (testing "start storage service again"
            (tka/start storage-app)
            (is (= 200 (:status (latest-commits-response))))

            ;; push a new commit up to the storage service
            (helpers/create-and-push-file local-repo-dir)

            ;; At this point, the storage service should have one more commit than
            ;; the client, since the client has not yet had time to sync with it,
            ;; so the SHA returned from latest-commits and the revision ID for the
            ;; client should not be the same
            (is (not= (get-latest-commits-for-repo repo)
                      (jgit-client/head-rev-id client-repo-dir))))

          (testing "verify client recovers"
            ;; wait two seconds for the client to poll again, then check that the
            ;; client has been synced to have the same latest commit as the
            ;; storage service
            (Thread/sleep 2000)
            (is (= (get-latest-commits-for-repo repo)
                   (jgit-client/head-rev-id client-repo-dir)))))

        (tka/stop storage-app)))))
