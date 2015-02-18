(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
              :as core]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.enterprise.jgit-client :as client]
            [me.raynes.fs :as fs]))

(deftest get-body-from-latest-commits-payload-test
  (testing "Can get latest commits"
    (is (= {"repo1" "123456", "repo2" nil}
           (core/get-body-from-latest-commits-payload
             {:headers {"content-type" "application/json"}
              :body "{\"repo1\": \"123456\", \"repo2\":null}"}))
        "Unexpected body received for get request"))
  (testing "Can't get latest commits for bad content type"
    (is (thrown-with-msg?
          Exception
          #"^Did not get json response for latest-commits.*"
          (core/get-body-from-latest-commits-payload
            {:headers {"content-type" "bogus"}
             :body    "{\"repo1\": \"123456\""}))))
  (testing "Can't get latest commits for no body"
    (is (thrown-with-msg?
          Exception
          #"^Did not get response body for latest-commits.*"
          (core/get-body-from-latest-commits-payload
            {:headers {"content-type" "application/json"}})))))

(defn process-repos-and-verify
  ([repos-to-verify client]
    (process-repos-and-verify repos-to-verify client false))
  ([repos-to-verify client ssl?]
    (core/process-repos-for-updates
      client
      (str (helpers/base-url ssl?) helpers/default-repo-path-prefix)
      (str (helpers/base-url ssl?) helpers/default-api-path-prefix)
      (into-array (map #(:process-repo %) repos-to-verify)))
    (doseq [repo repos-to-verify]
      (let [target-dir (get-in repo [:process-repo :target-dir])]
        (is (= (client/head-rev-id (:origin-dir repo))
               (client/head-rev-id target-dir))
            (str "Unexpected head revision in target repo directory : "
                 target-dir))))))

(defn test-process-repos-for-updates
  [ssl?]
  (let [git-base-dir (helpers/temp-dir-as-string)
        server-repo-subpath-1 "process-repos-test-1.git"
        server-repo-subpath-2 "process-repos-test-2.git"
        server-repo-subpath-3 "process-repos-test-3.git"
        config-fn             (if ssl?
                                helpers/jgit-ssl-config-with-repos
                                helpers/jgit-plaintext-config-with-repos)
        client-opts           (if ssl?
                                {:ssl-ca-cert "./dev-resources/ssl/ca.pem"
                                 :ssl-cert    "./dev-resources/ssl/cert.pem"
                                 :ssl-key     "./dev-resources/ssl/key.pem"}
                                {})]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (config-fn
        git-base-dir
        [{:sub-path server-repo-subpath-1}
         {:sub-path server-repo-subpath-2}
         {:sub-path server-repo-subpath-3}])
      (let [client-orig-repo-dir-1 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-1
                                     1
                                     ssl?)
            client-orig-repo-dir-2 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-2
                                     2
                                     ssl?)
            client-orig-repo-dir-3 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-3
                                     0
                                     ssl?)
            client-targ-repo-dir-1 (helpers/temp-dir-as-string)
            client-targ-repo-dir-2 (helpers/temp-dir-as-string)
            client-targ-repo-dir-3 (helpers/temp-dir-as-string)
            repos-to-verify [{:origin-dir   client-orig-repo-dir-1
                              :process-repo {
                                             :name       server-repo-subpath-1
                                             :target-dir client-targ-repo-dir-1}}
                             {:origin-dir   client-orig-repo-dir-2
                              :process-repo {
                                             :name       server-repo-subpath-2
                                             :target-dir client-targ-repo-dir-2}}
                             {:origin-dir   client-orig-repo-dir-3
                              :process-repo {
                                             :name       server-repo-subpath-3
                                             :target-dir client-targ-repo-dir-3}}]
            client (sync/create-client client-opts)]
        (testing "Validate initial repo update"
          (fs/delete-dir client-targ-repo-dir-1)
          (fs/delete-dir client-targ-repo-dir-2)
          (fs/delete-dir client-targ-repo-dir-3)
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "Files pulled for update"
          (helpers/create-and-push-file client-orig-repo-dir-2)
          (helpers/create-and-push-file client-orig-repo-dir-3)
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "No change when nothing pushed"
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "Files restored after repo directory deleted"
          (fs/delete-dir client-targ-repo-dir-2)
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "Client directory not created when no match on server"
          (logging/with-test-logging
            (let [client-targ-repo-nonexistent (helpers/temp-dir-as-string)]
              (fs/delete-dir client-targ-repo-nonexistent)
              (core/process-repos-for-updates
                client
                (str (helpers/base-url ssl?) helpers/default-repo-path-prefix)
                (str (helpers/base-url ssl?) helpers/default-api-path-prefix)
                [{:name       "process-repos-test-nonexistent.git"
                  :target-dir client-targ-repo-nonexistent}])
              (is (not (fs/exists? client-targ-repo-nonexistent))
                  "Found client directory despite no matching repo on server")
              (is
                (logged?
                  #"^File sync did not find.*process-repos-test-nonexistent.git"
                  :error)))))))))

(deftest process-repos-for-updates-test
  (testing "process-repos-for-updates works over http"
    (test-process-repos-for-updates false))

  ; This test is a little strange. Configuring SSL for the JGit client works by using a static
  ; method. As a result, since we're running the storage service, that ends up configuring SSL
  ; for the client service as well.
  (testing "process-repos-for-updates works over https when SSL is configured"
    (test-process-repos-for-updates true)))
