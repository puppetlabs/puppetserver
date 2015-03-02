(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
             :refer :all]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.enterprise.jgit-client :as client]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [try+]]
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
    (let [got-expected-error? (atom false)]
      (try+
        (get-body-from-latest-commits-payload
          {:status  200
           :headers {"content-type" "bogus"}
           :body    "{\"repo1\": \"123456\""})
        (catch map? error
          (reset! got-expected-error? true)
          (is (re-matches #"Response for latest commits unexpected.*content-type.*bogus.*"
                          (:message error)))))
      (is @got-expected-error?)))

  (testing "Can't get latest commits for no body"
    (let [got-expected-error? (atom false)]
      (try+
        (get-body-from-latest-commits-payload
          {:status  200
           :headers {"content-type" "application/json"}})
        (catch map? error
          (reset! got-expected-error? true)
          (is (re-matches #"Response for latest commits unexpected.*body.*missing.*"
                          (:message error)))))
      (is @got-expected-error?))))

(defn process-repos-and-verify
  ([repos-to-verify client]
    (process-repos-and-verify repos-to-verify client false))
  ([repos-to-verify client ssl?]
    (process-repos-for-updates
      client
      (str (helpers/base-url ssl?) helpers/default-repo-path-prefix)
      (str (helpers/base-url ssl?) helpers/default-api-path-prefix)
      (into {} (map #(:process-repo %) repos-to-verify)))
    (doseq [repo repos-to-verify]
      (let [name       (:name repo)
            target-dir (get-in repo [:process-repo (keyword name)])]
        (is (= (client/head-rev-id-from-working-tree (:origin-dir repo))
               (client/head-rev-id-from-git-dir target-dir))
            (str "Unexpected head revision in target repo directory : "
                 target-dir))))))

(defn test-process-repos-for-updates
  [ssl?]
  (let [git-base-dir (helpers/temp-dir-as-string)
        server-repo-subpath-1 "process-repos-test-1.git"
        server-repo-subpath-2 "process-repos-test-2.git"
        server-repo-subpath-3 "process-repos-test-3.git"
        client-opts           (if ssl?
                                {:ssl-ca-cert "./dev-resources/ssl/ca.pem"
                                 :ssl-cert    "./dev-resources/ssl/cert.pem"
                                 :ssl-key     "./dev-resources/ssl/key.pem"}
                                {})]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/jgit-config-with-repos
        git-base-dir
        [{:sub-path server-repo-subpath-1}
         {:sub-path server-repo-subpath-2}
         {:sub-path server-repo-subpath-3}]
        ssl?)
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
            repos-to-verify [{:name         server-repo-subpath-1
                              :origin-dir   client-orig-repo-dir-1
                              :process-repo {(keyword server-repo-subpath-1)
                                               client-targ-repo-dir-1}}
                             {:name         server-repo-subpath-2
                              :origin-dir   client-orig-repo-dir-2
                              :process-repo {(keyword server-repo-subpath-2)
                                               client-targ-repo-dir-2}}
                             {:name         server-repo-subpath-3
                              :origin-dir   client-orig-repo-dir-3
                              :process-repo {(keyword server-repo-subpath-3)
                                               client-targ-repo-dir-3}}]
            client (sync/create-client client-opts)]
        (testing "Validate initial repo update"
          (fs/delete-dir client-targ-repo-dir-1)
          (fs/delete-dir client-targ-repo-dir-2)
          (fs/delete-dir client-targ-repo-dir-3)
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "Files fetched for update"
          (helpers/create-and-push-file client-orig-repo-dir-2)
          (helpers/create-and-push-file client-orig-repo-dir-3)
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "No change when nothing pushed"
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "Files restored after repo directory deleted"
          (fs/delete-dir client-targ-repo-dir-2)
          (process-repos-and-verify repos-to-verify client ssl?))
        (testing "Client directory not created when no match on server"
          (with-test-logging
            (let [client-targ-repo-nonexistent (helpers/temp-dir-as-string)]
              (fs/delete-dir client-targ-repo-nonexistent)
              (process-repos-for-updates
                client
                (str (helpers/base-url ssl?) helpers/default-repo-path-prefix)
                (str (helpers/base-url ssl?) helpers/default-api-path-prefix)
                {:process-repos-test-nonexistent.git
                   client-targ-repo-nonexistent})
              (is (not (fs/exists? client-targ-repo-nonexistent))
                  "Found client directory despite no matching repo on server")
              (is
                (logged?
                  #"^File sync did not find.*process-repos-test-nonexistent.git"
                  :error)))))))))

(deftest process-repos-for-updates-test
  (testing "process-repos-for-updates works over http"
    (test-process-repos-for-updates false))

  (testing "process-repos-for-updates works over https when SSL is configured"
    ; The client service is not being started, so we need to configure JGit to use SSL
    (helpers/configure-JGit-SSL! true)
    (test-process-repos-for-updates true)))

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
