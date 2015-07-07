(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.core :refer [service] :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty-service]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.trapperkeeper.services.status.status-service :as status-service]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service
             :as file-sync-storage-service]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
             :as file-sync-client-service]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service
             :as scheduler-service]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.protocols.file-sync-client :as client-protocol]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [me.raynes.fs :as fs])
  (:import (javax.net.ssl SSLException)))

(use-fixtures :once schema-test/validate-schemas)

(def file-sync-client-ssl-config
  (helpers/client-service-config (helpers/temp-dir-as-string) ["fake"] true))

(defn ring-handler
  [_]
  {:status  200
   :body    (json/encode {:result {:commit "Successful connection over SSL"}})
   :headers {"content-type" "application/json"}})

(deftest ^:integration polling-client-ssl-test
  (testing "polling client will use SSL when configured"
    (logging/with-test-logging
      (helpers/with-bootstrapped-client-service-and-webserver
        app
        helpers/webserver-ssl-config
        ring-handler
        file-sync-client-ssl-config
        (let [sync-agent (helpers/get-sync-agent app)]
          (let [new-state (helpers/wait-for-new-state sync-agent)]
            (is (= :successful (:status new-state)))))))))

(deftest ^:integration polling-client-no-ssl-test
  (testing "polling client fails to use SSL when not configured"
    (logging/with-test-logging
      (helpers/with-bootstrapped-client-service-and-webserver
        app
        helpers/webserver-ssl-config
        ring-handler
        (update-in file-sync-client-ssl-config [:file-sync-client] dissoc :ssl-ca-cert :ssl-cert :ssl-key)
        (let [sync-agent (helpers/get-sync-agent app)
              new-state (helpers/wait-for-new-state sync-agent)]
          (is (= :failed (:status new-state)))
          (is (instance? SSLException (:cause (:error new-state)))))))))

(deftest ^:integration ssl-config-test
  (testing "SSL configuration fails when not all options are provided"
    (logging/with-test-logging
      (is (thrown? IllegalArgumentException
                   (helpers/with-bootstrapped-client-service-and-webserver
                     app
                     helpers/webserver-ssl-config
                     ring-handler
                     (update-in file-sync-client-ssl-config [:file-sync-client] dissoc :ssl-ca-cert)))))))

(deftest ^:integration working-dir-sync-test
  (let [repo "repo"
        root-data-dir (helpers/temp-dir-as-string)
        client-repo-dir (common/bare-repo (core/path-to-data-dir root-data-dir) repo)
        client-working-dir (fs/file (helpers/temp-dir-as-string))
        local-repo-dir (helpers/temp-dir-as-string)
        local-repo (helpers/init-repo! (fs/file local-repo-dir))]
    (logging/with-test-logging
      (helpers/init-bare-repo! client-repo-dir)

      ;; Commit a test file so the repo has contents to sync
      (fs/touch (fs/file local-repo-dir "test"))
      (jgit-utils/add-and-commit local-repo "a test commit" helpers/test-identity)
      (jgit-utils/push local-repo (str client-repo-dir))
      (bootstrap/with-app-with-config
        app
        [file-sync-client-service/file-sync-client-service
         scheduler-service/scheduler-service]
        (helpers/client-service-config
          root-data-dir
          [repo]
          false)

        (let [client-service (tk-app/get-service app :FileSyncClientService)]
          (fs/mkdir client-working-dir)

          (testing "local dir and client working dir should have correct contents"
            (is (nil? (fs/list-dir client-working-dir)))
            (is (not (nil? (fs/list-dir local-repo-dir))))
            (is (= 2 (count (fs/list-dir local-repo-dir)))))

          (testing (str "client working dir is properly synced when "
                        "service function is called")
            (client-protocol/sync-working-dir! client-service
                                               (keyword repo)
                                               (str client-working-dir))
            (is (= (fs/list-dir client-working-dir)
                   (filter #(not= ".git" %)
                           (fs/list-dir local-repo-dir))))
            (is (not (nil? (fs/list-dir client-working-dir))))))))))

(deftest ^:integration working-dir-sync-with-submodules-test
  (testing "sync-working-dir works with submodules"
    (let [repo "parent-repo"
          submodules-working-dir (helpers/temp-dir-as-string)
          submodules-dir-name "submodules"
          submodule "submodule"
          test-file "test.txt"
          root-data-dir (helpers/temp-dir-as-string)
          client-data-dir (core/path-to-data-dir root-data-dir)
          git-dir (common/bare-repo (str root-data-dir "/storage") repo)
          client-git-dir (common/bare-repo client-data-dir repo)
          client-working-dir (helpers/temp-dir-as-string)
          publish-url (str helpers/server-base-url
                           helpers/default-api-path-prefix
                           "/v1"
                           common/publish-content-sub-path)
          storage-app (tk-app/check-for-errors!
                        (tk/boot-services-with-config
                          [jetty-service/jetty9-service
                           file-sync-storage-service/file-sync-storage-service
                           status-service/status-service
                           webrouting-service/webrouting-service]
                          (helpers/storage-service-config
                            root-data-dir
                            {(keyword repo) {:working-dir (helpers/temp-dir-as-string)
                                             :submodules-dir submodules-dir-name
                                             :submodules-working-dir submodules-working-dir}})))]

      ;; Set up the submodule and ensure it's added to the remote repository
      (ks/mkdirs! (fs/file submodules-working-dir submodule))
      (helpers/write-test-file! (fs/file submodules-working-dir submodule test-file))
      (http-client/post publish-url)
      (bootstrap/with-app-with-config
        app
        [file-sync-client-service/file-sync-client-service
         scheduler-service/scheduler-service]
        (helpers/client-service-config
          root-data-dir
          [repo]
          false)
        (let [client-service (tk-app/get-service app :FileSyncClientService)
              sync-agent (helpers/get-sync-agent app)]
          (fs/mkdir client-working-dir)

          (testing "client working dir should be empty"
            (is (nil? (fs/list-dir client-working-dir))))

          ;; Sync the repo so we can sync the working dir
          (let [new-state (helpers/wait-for-new-state sync-agent)]
            (is (= :successful (:status new-state))))

          (testing "Repo config has proper submodule URLs"
            (let [parent-repo (jgit-utils/get-repository-from-git-dir client-git-dir)
                  repo-config (.getConfig parent-repo)
                  submodule-client-dir (str (common/submodule-bare-repo client-data-dir repo submodule))]
              (is (= submodule-client-dir
                    (.getString
                      repo-config
                      "submodule"
                      (str submodules-dir-name "/" submodule)
                      "url")))))

          ;; Turn off the storage service to ensure submodules are being
          ;; fetched from their local client-side copies
          (tk-app/stop storage-app)

          (testing "new submodules are properly initialized and updated from local copies"
            (client-protocol/sync-working-dir!
              client-service
              (keyword repo)
              (str client-working-dir))
            (is (fs/exists? (fs/file
                              client-working-dir
                              submodules-dir-name
                              submodule)))
            (is (fs/exists? (fs/file
                              client-working-dir
                              submodules-dir-name
                              submodule
                              test-file)))
            (let [submodules-status (jgit-utils/get-submodules-latest-commits
                                      git-dir
                                      (fs/file submodules-working-dir submodule))
                  latest-commit (get submodules-status (str submodules-dir-name "/" submodule))]
              (is (= latest-commit
                     (jgit-utils/head-rev-id-from-working-tree (fs/file
                                                                 client-working-dir
                                                                 submodules-dir-name
                                                                 submodule))))))

          (tk-app/start storage-app)

          ;; Make an additional commit to ensure that previously updated submodules
          ;; are synced
          (fs/delete (fs/file submodules-working-dir submodule test-file))
          (http-client/post publish-url)

          ;; Sync the repo so we can get the latest changes
          (let [new-state (helpers/wait-for-new-state sync-agent)]
            (is (= :successful (:status new-state))))

          ;; Turn off the storage service to ensure submodules are being
          ;; fetched from their local client-side copies
          (tk-app/stop storage-app)

          (testing "existing submodules are properly updated"
            (client-protocol/sync-working-dir!
              client-service
              (keyword repo)
              (str client-working-dir))
            (is (fs/exists? (fs/file
                              client-working-dir
                              submodules-dir-name
                              submodule)))
            (is (not (fs/exists? (fs/file
                                   client-working-dir
                                   submodules-dir-name
                                   submodule test-file))))
            (let [submodules-status (jgit-utils/get-submodules-latest-commits
                                      git-dir
                                      (fs/file submodules-working-dir submodule))
                  latest-commit (get submodules-status (str submodules-dir-name "/" submodule))]
              (is (= latest-commit
                     (jgit-utils/head-rev-id-from-working-tree (fs/file
                                                                 client-working-dir
                                                                 submodules-dir-name
                                                                 submodule)))))))))))

(deftest register-callback-test
  (testing "Callbacks must be registered before the File Sync Client is started"
    (let [my-service (service [[:FileSyncClientService register-callback!]]
                       (start [this context]
                         (register-callback! #{"foo"} (fn [& _] nil))))
          config (helpers/client-service-config (helpers/temp-dir-as-string) [] false)
          client-app (tk/build-app
                       [file-sync-client-service/file-sync-client-service
                        scheduler-service/scheduler-service
                        my-service]
                       config)]
      (logging/with-test-logging ; necessary because Trapperkeeper logs the error
        (tk-app/init client-app)
        (is (thrown-with-msg?
              IllegalStateException
              #"Callbacks must be registered before the File Sync Client is started"
              (tk-app/start client-app)))
        (tk-app/stop client-app)))))
