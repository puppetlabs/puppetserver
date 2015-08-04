(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service-test
  (:require [clojure.test :refer :all]
            [clojure.walk :as walk]
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
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.protocols.file-sync-client :as client-protocol]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [me.raynes.fs :as fs]
            [clj-time.core :as time]
            [clj-time.format :as time-format])
  (:import (javax.net.ssl SSLException)))

(use-fixtures :once schema-test/validate-schemas)

(def file-sync-client-ssl-config
  (helpers/client-service-config (helpers/temp-dir-as-string) true))

(defn ring-handler
  [_]
  {:status  200
   :body    (json/encode {})
   :headers {"content-type" "application/json"}})

(deftest ^:integration polling-client-ssl-test
  (testing "polling client will use SSL when configured"
    (logging/with-test-logging
      (helpers/with-bootstrapped-client-service-and-webserver
        app
        (helpers/webserver-ssl-config)
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
        (helpers/webserver-ssl-config)
        ring-handler
        (update-in file-sync-client-ssl-config [:file-sync-client] dissoc :ssl-ca-cert :ssl-cert :ssl-key)
        (let [sync-agent (helpers/get-sync-agent app)
              new-state (helpers/wait-for-new-state sync-agent)]
          (is (= :failed (:status new-state)))
          (is (instance? SSLException (:cause (:error new-state)))))))))

(deftest ^:integration ssl-config-test
  (testing "SSL configuration fails when not all options are provided"
    (logging/with-test-logging
      (let [client-app (tk/build-app
                         helpers/client-service-and-deps
                         (update-in file-sync-client-ssl-config [:file-sync-client] dissoc :ssl-ca-cert))]
        (is (thrown? IllegalArgumentException
              (tk-app/init client-app)
              (tk-app/start client-app)))
        (tk-app/stop client-app)))))

(deftest ^:integration working-dir-sync-test
  (let [repo "repo"
        root-data-dir (helpers/temp-dir-as-string)
        client-repo-dir (common/bare-repo-path (core/path-to-data-dir root-data-dir) repo)
        client-working-dir (fs/file (helpers/temp-dir-as-string))
        local-repo-dir (helpers/temp-dir-as-string)
        local-repo (helpers/init-repo! (fs/file local-repo-dir))]
    (logging/with-test-logging
      (helpers/init-bare-repo! client-repo-dir)

      ;; Commit a test file so the repo has contents to sync
      (fs/touch (fs/file local-repo-dir "test"))
      (jgit-utils/add-and-commit local-repo "a test commit" helpers/test-person-ident)
      (jgit-utils/push local-repo (str client-repo-dir))
      (bootstrap/with-app-with-config
        app
        helpers/client-service-and-deps
        (helpers/client-service-config
          root-data-dir
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
          git-dir (common/bare-repo-path (str root-data-dir "/storage") repo)
          client-git-dir (common/bare-repo-path client-data-dir repo)
          client-working-dir (helpers/temp-dir-as-string)
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
      (http-client/post helpers/publish-url)
      (bootstrap/with-app-with-config
        app
        helpers/client-service-and-deps
        (helpers/client-service-config
          root-data-dir
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
                  submodule-client-dir (str (common/submodule-bare-repo-path client-data-dir repo submodule))]
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
          (http-client/post helpers/publish-url)

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

(deftest ^:integration register-callback-test
  (testing "Callbacks must be registered before the File Sync Client is started"
    (let [my-service (service [[:FileSyncClientService register-callback!]]
                       (start [this context]
                         (register-callback! #{"foo"} (fn [& _] nil))))
          config (helpers/client-service-config (helpers/temp-dir-as-string) false)
          client-app (tk/build-app
                       (conj helpers/client-service-and-deps my-service)
                       config)]
      (logging/with-test-logging ; necessary because Trapperkeeper logs the error
        (tk-app/init client-app)
        (is (thrown-with-msg?
              IllegalStateException
              #"Callbacks must be registered before the File Sync Client is started"
              (tk-app/start client-app)))
        (tk-app/stop client-app)))))

(deftest ^:integration status-endpoint-test
  (let [test-start-time (time/now)
        data-dir (helpers/temp-dir-as-string)
        repo "repo"
        working-dir (helpers/temp-dir-as-string)]
    (bootstrap/with-app-with-config
      app
      helpers/file-sync-services-and-deps
      (helpers/file-sync-config data-dir {(keyword repo) {:working-dir working-dir}})

      (let [sync-agent (helpers/get-sync-agent app)]
        (testing "basic status info"
          (let [response (helpers/get-client-status)
                body (json/parse-string (:body response))
                status (get body "status")]
            (is (= "running" (get body "state")))
            (is (time/before? (helpers/parse-timestamp (get status "timestamp"))
                  (time/now)))

            ;; The values are not checked, as they may or may not be nil
            ;; depending on whether or not a sync has been performed
            (is (contains? status "last_successful_sync_time"))
            (is (contains? status "last_check_in"))))

        (let [new-state (helpers/wait-for-new-state sync-agent)]
          (is (= :successful (:status new-state))))

        (let [response (helpers/get-client-status)
              body (json/parse-string (:body response))]
          (is (= "running" (get body "state")))
          (testing "status info contains info on recent check-ins"
            (let [check-in-time (helpers/parse-timestamp (get-in body
                                                           ["status"
                                                            "last_check_in"
                                                            "timestamp"]))
                  sync-time (helpers/parse-timestamp (get-in body
                                                       ["status"
                                                        "last_successful_sync_time"]))]
              (is (time/before? sync-time (time/now)))
              (is (time/before? check-in-time (time/now)))
              (is (time/before? check-in-time sync-time)))

            (testing "status contains info on response from latest check-in"
              (let [latest-commits (helpers/get-latest-commits)
                    latest-response (walk/keywordize-keys (get-in body ["status" "last_check_in" "response"]))]
                (is (= latest-commits latest-response))))

            (testing "contains nil latest commit if no commits are present"
              (let [latest-commit (get-in body ["status" "repos" repo "latest_commit"])]
                (is (nil? latest-commit))))))

        (testing "Getting the status when commits are present in the repo"
          (spit (fs/file working-dir "test-file") "test file content")
          (let [publish-request-body (-> {:message "my msg"
                                          :author {:name "Testy"
                                                   :email "test@foo.com"}}
                                       json/generate-string)
                commit-id (-> (helpers/do-publish publish-request-body)
                            :body
                            (json/parse-string true)
                            :repo
                            :commit)]
            (let [new-state (helpers/wait-for-new-state sync-agent)]
              (is (= :successful (:status new-state))))

            (let [response (helpers/get-client-status)
                  body (json/parse-string (:body response))
                  latest-commit-status (get-in body ["status" "repos" repo "latest_commit"])]
              (testing "Latest commit ID"
                (is (= commit-id (get latest-commit-status "commit"))))
              (testing "Commit date/time"
                (let [commit-time (time-format/parse (get latest-commit-status "date"))]
                  (is (time/after? commit-time (-> test-start-time
                                                 (time/minus (time/seconds 1)))))
                  (is (time/before? commit-time (time/now)))))
              (testing "The commit author"
                (is (= {"name" "Testy"
                        "email" "test@foo.com"}
                      (get latest-commit-status "author"))))
              (testing "The commit message"
                (is (= "my msg" (get latest-commit-status "message")))))))

        (testing "status level is honored"
          (let [response (helpers/get-client-status :critical)
                body (json/parse-string (:body response))]
            (is (= "running" (get body "state")))
            (is (nil? (get body "status")))
            (is (contains? body "status"))))))))

(deftest ^:integration status-endpoint-submodules-test
  (let [test-start-time (time/now)
        data-dir (helpers/temp-dir-as-string)
        working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodules-working-dir (helpers/temp-dir-as-string)
        submodule-name "submodule"
        submodule-path (str submodules-dir "/" submodule-name)]
    (bootstrap/with-app-with-config
      app
      helpers/file-sync-services-and-deps
      (helpers/file-sync-config data-dir {:repo {:working-dir working-dir
                                                 :submodules-dir submodules-dir
                                                 :submodules-working-dir submodules-working-dir}})

      (fs/mkdirs (fs/file submodules-working-dir submodule-name))
      (spit
        (fs/file submodules-working-dir submodule-name "test-file")
        "submodules content")
      (let [publish-request-body (-> {:message "my msg"
                                      :author {:name "Testy"
                                               :email "test@foo.com"}}
                                   json/generate-string)
            submodule-commit-id (-> (helpers/do-publish publish-request-body)
                                  :body
                                  json/parse-string
                                  (get-in ["repo"
                                           "submodules"
                                           submodule-path]))
            sync-agent (helpers/get-sync-agent app)]

        (let [new-state (helpers/wait-for-new-state sync-agent)]
          (is (= :successful (:status new-state))))

        (let [response (helpers/get-client-status)
              body (json/parse-string (:body response))
              latest-commit-status (get-in body ["status" "repos" "repo" "submodules" submodule-path])]
          (is (= 200 (:status response)))
          (testing "Latest commit ID"
            (is (= submodule-commit-id (get latest-commit-status "commit"))))
          (testing "Commit date/time"
            (let [commit-time (time-format/parse (get latest-commit-status "date"))]
              (is (time/after? commit-time (-> test-start-time
                                             (time/minus (time/seconds 1)))))
              (is (time/before? commit-time (time/now)))))
          (testing "The commit author"
            (is (= {"name" "Testy"
                    "email" "test@foo.com"}
                  (get latest-commit-status "author"))))
          (testing "The commit message"
            (is (= "my msg" (get latest-commit-status "message")))))))))

(deftest ^:integration get-working-dir-status-test
  (let [data-dir (helpers/temp-dir-as-string)
        working-dir (helpers/temp-dir-as-string)
        submodules-dir "submodules"
        submodules-working-dir (helpers/temp-dir-as-string)
        submodule-name "submodule"
        submodule-path (str submodules-dir "/" submodule-name)
        client-working-dir (helpers/temp-dir-as-string)]
    (bootstrap/with-app-with-config
      app
      helpers/file-sync-services-and-deps
      (helpers/file-sync-config data-dir {:repo {:working-dir working-dir
                                                 :submodules-dir submodules-dir
                                                 :submodules-working-dir submodules-working-dir}})

      ; First, create and publish some test content
      (spit (fs/file working-dir "test-file-1") "test file content 1")
      (spit (fs/file working-dir "test-file-2") "test file content 2")
      (fs/mkdirs (fs/file submodules-working-dir submodule-name))
      (spit
        (fs/file submodules-working-dir submodule-name "test-file")
        "submodules content")
      (let [publish-request-body (-> {:message "my msg"
                                      :author {:name "Testy"
                                               :email "test@foo.com"}}
                                   json/generate-string)
            submodule-commit-id (-> (helpers/do-publish publish-request-body)
                                  :body
                                  json/parse-string
                                  (get-in ["repo"
                                           "submodules"
                                           submodule-path]))
            sync-agent (helpers/get-sync-agent app)]

        (let [new-state (helpers/wait-for-new-state sync-agent)]
          (is (= :successful (:status new-state))))

        (let [client-service (tk-app/get-service app :FileSyncClientService)]
          (testing "error thrown when desired repo does not exist"
            (is (thrown? IllegalArgumentException
                  (client-protocol/get-working-dir-status
                    client-service :fake client-working-dir))))

          (testing "returns nil if desired working dir does not exist"
            (fs/delete-dir client-working-dir)
            (is (nil? (client-protocol/get-working-dir-status
                        client-service :repo client-working-dir))))

          (testing "get-working-dir-status returns correct status info"
            (fs/mkdir client-working-dir)
            (client-protocol/sync-working-dir!
              client-service :repo client-working-dir)
            (testing "when the working-dir is clean"
              (let [status (client-protocol/get-working-dir-status
                             client-service :repo client-working-dir)]
                (is (= (get status :status)
                      {:clean true
                       :missing #{}
                       :modified #{}
                       :untracked #{}}))))

            (testing "when the working-dir is dirty"
              ; Modify an existing file.
              (spit (fs/file client-working-dir "test-file-1") "cats")
              ; Delete an existing file
              (fs/delete (fs/file client-working-dir "test-file-2"))
              ; Create a new file.
              (spit (fs/file client-working-dir "new-test-file") "cats are the best")

              (let [status (client-protocol/get-working-dir-status
                             client-service :repo client-working-dir)]
                (is (= (get status :status)
                       {:clean false
                        :missing #{"test-file-2"}
                        :modified #{"test-file-1"}
                        :untracked #{"new-test-file"}}))))

            (testing "returns correct submodule status info"
              (let [status (client-protocol/get-working-dir-status
                             client-service :repo client-working-dir)]
                (is (= (get status :submodules)
                       {submodule-path {:head_id submodule-commit-id
                                        :path submodule-path
                                        :status "INITIALIZED"}}))))))))))

(deftest ^:integration config-validation-test
  (let [config (helpers/client-service-config (helpers/temp-dir-as-string))]
    (logging/with-test-logging
      (testing "the client service correctly validates its configuration"
        (let [app (tk/build-app
                    helpers/client-service-and-deps
                    (dissoc config :file-sync-client))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                #"Value does not match schema"
                (tk-app/init app)
                (tk-app/start app)))
          (tk-app/stop app)))

      (testing "the client service correctly validates the common configuration"
        (let [app (tk/build-app
                    helpers/client-service-and-deps
                    (dissoc config :file-sync-common))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                #"Value does not match schema"
                (tk-app/init app))))))))
