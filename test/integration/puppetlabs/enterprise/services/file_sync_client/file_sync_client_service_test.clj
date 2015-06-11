(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.core :refer [service] :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
             :as file-sync-client-service]
            [puppetlabs.enterprise.services.scheduler.scheduler-service
             :as scheduler-service]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.protocols.file-sync-client :as client-protocol]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [me.raynes.fs :as fs])
  (:import (javax.net.ssl SSLException)))

(use-fixtures :once schema-test/validate-schemas)

(def file-sync-client-ssl-config
  (helpers/client-service-config-with-repos (helpers/temp-dir-as-string) ["fake"] true))

(defn ring-handler
  [_]
  {:status  200
   :body    (json/encode {:result {:commit "Successful connection over SSL"}})
   :headers {"content-type" "application/json"}})

(deftest ^:integration polling-client-ssl-test
  (testing "polling client will use SSL when configured"
    (logging/with-test-logging
      (helpers/with-bootstrapped-file-sync-client-and-webserver
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
      (helpers/with-bootstrapped-file-sync-client-and-webserver
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
                   (helpers/with-bootstrapped-file-sync-client-and-webserver
                     app
                     helpers/webserver-ssl-config
                     ring-handler
                     (update-in file-sync-client-ssl-config [:file-sync-client] dissoc :ssl-ca-cert)))))))

(deftest ^:integration working-dir-sync-test
  (let [repo "repo"
        root-data-dir (helpers/temp-dir-as-string)
        client-repo-dir (str (core/path-to-data-dir root-data-dir)
                             "/"
                             repo
                             ".git")
        client-working-dir (fs/file (helpers/temp-dir-as-string))
        local-repo-dir (helpers/temp-dir-as-string)
        local-repo (helpers/init-repo! (fs/file local-repo-dir))]
    (logging/with-test-logging
      (helpers/init-bare-repo! (fs/file client-repo-dir))

      ;; Commit a test file so the repo has contents to sync
      (fs/touch (fs/file local-repo-dir "test"))
      (jgit-utils/add-and-commit local-repo "a test commit" helpers/author)
      (jgit-utils/push local-repo client-repo-dir)
      (bootstrap/with-app-with-config
        app
        [file-sync-client-service/file-sync-client-service
         scheduler-service/scheduler-service]
        (helpers/client-service-config-with-repos
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
                                               repo
                                               (str client-working-dir))
            (is (= (fs/list-dir client-working-dir)
                   (filter #(not= ".git" %)
                           (fs/list-dir local-repo-dir))))
            (is (not (nil? (fs/list-dir client-working-dir))))))))))

(deftest register-callback-test
  (testing "Callbacks must be registered before the File Sync Client is started"
    (let [my-service (service [[:FileSyncClientService register-callback!]]
                       (start [this context]
                         (register-callback! :foo (fn [& _] nil))))
          config (helpers/client-service-config-with-repos (helpers/temp-dir-as-string) [] false)
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
