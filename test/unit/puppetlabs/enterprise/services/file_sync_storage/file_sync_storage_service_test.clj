(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service-test
  (:import  (org.eclipse.jgit.api.errors TransportException))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as core]
            [puppetlabs.enterprise.jgit-client :as client]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.http.client.sync :as http-client]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [puppetlabs.enterprise.jgit-client :as jgit-client]))

(defn parse-response-body
  [response]
  (cheshire/parse-string (slurp (:body response))))

(defn simple-workflow
  [git-base-dir server-repo-subpath ssl?]
  (let [config-fn (if ssl?
                    helpers/jgit-ssl-config-with-repos
                    helpers/jgit-plaintext-config-with-repos)]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (config-fn
        git-base-dir
        [{:sub-path server-repo-subpath}])
      (let [client-orig-repo-dir (helpers/temp-dir-as-string)
            server-repo-url (str
                              (helpers/repo-base-url ssl?)
                              "/"
                              server-repo-subpath)
            repo-test-file "tester"
            client-orig-repo (helpers/clone-and-validate
                               server-repo-url
                               client-orig-repo-dir)]
        (helpers/create-and-push-file
          client-orig-repo
          client-orig-repo-dir
          repo-test-file)
        (let [client-second-repo-dir
              (helpers/temp-dir-as-string)]
          (helpers/clone-and-validate
            server-repo-url
            client-second-repo-dir)
          (is (= helpers/file-text
                 (slurp (str client-second-repo-dir "/" repo-test-file)))
              "Unexpected file text found in second repository clone"))))))

(deftest push-disabled-test
  (testing "The JGit servlet should not accept pushes unless configured to do so"
    (let [server-repo-subpath "push-disabled-test.git"
          config (merge (helpers/webserver-plaintext-config)
                        {:file-sync-storage {:base-path (helpers/temp-dir-as-string)
                                             :repos     [{:sub-path server-repo-subpath}]}})]
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        config
        (let [client-orig-repo-dir (helpers/temp-dir-as-string)
              server-repo-url      (str
                                     (helpers/repo-base-url)
                                     "/"
                                     server-repo-subpath)
              repo-test-file       "some random file contents"
              client-orig-repo     (helpers/clone-and-validate
                                     server-repo-url
                                     client-orig-repo-dir)]

          (testing "An attempt to push to the repo should fail"
            (is (thrown-with-msg?
                  TransportException
                  #"Git access forbidden"
                  (helpers/create-and-push-file
                    client-orig-repo
                    client-orig-repo-dir
                    repo-test-file)))))))))

(deftest file-sync-storage-service-simple-workflow-test
  (let [git-base-dir (helpers/temp-dir-as-string)
        server-repo-subpath "file-sync-storage-service-simple-workflow.git"]
    (testing "bootstrap the file sync storage service and validate that a simple
            clone/push/clone to the server works over http"
      (simple-workflow git-base-dir server-repo-subpath false))

    (testing "bootstrap the file sync storage service and validate that a simple
            clone/push/clone to the server works over https when SSL is configured"
      (simple-workflow git-base-dir server-repo-subpath true))

    (testing "file sync storage service cannot perform git operations over plaintext when
              the server is configured using SSL"
      (helpers/with-bootstrapped-file-sync-storage-service-for-http
        app
        (helpers/jgit-ssl-and-plaintext-config-with-repos
          git-base-dir
          [{:sub-path server-repo-subpath}])
        (let [client-orig-repo-dir (helpers/temp-dir-as-string)
              server-repo-url (str
                                (helpers/repo-base-url true)
                                "/"
                                server-repo-subpath)]
          (is (thrown? TransportException (jgit-client/clone server-repo-url client-orig-repo-dir))))))))

(deftest configurable-endpoints-test
  (let [repo-path             "/test-repo-path"
        api-path              "/test-api-path"
        server-repo-subpath   "file-sync-storage-service-simple-workflow.git"
        config                {:file-sync-storage
                                {:base-path (helpers/temp-dir-as-string)
                                 :repos [{:sub-path server-repo-subpath}]}
                               :webserver {:port helpers/http-port}
                               :web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                                                     {:api          api-path
                                                      :repo-servlet repo-path}}}]

    (helpers/with-bootstrapped-file-sync-storage-service-for-http app config
      (let [client-orig-repo-dir  (helpers/temp-dir-as-string)
            server-repo-url       (str
                                    (helpers/repo-base-url repo-path false)
                                    "/"
                                    server-repo-subpath)]

        (testing "The URL path at which the service mounts "
                  "the JGit servlet is configurable"
          (testing "Clone and verify the repo"
            (helpers/clone-and-validate
              server-repo-url
              client-orig-repo-dir)))

        (testing "The URL path at which the service mounts "
                 "the ring app is configurable"
          (testing "We can make a basic HTTP request to the server"
            (let [response (http-client/get
                             (str
                               helpers/server-base-url
                               api-path
                               common/latest-commits-sub-path))]
              (is (= 200 (:status response))))))))))

(deftest latest-commits-test
  (let [git-base-dir (helpers/temp-dir-as-string)
        server-repo-subpath-1 "latest-commits-test-1.git"
        server-repo-subpath-2 "latest-commits-test-2.git"
        server-repo-subpath-no-commits "latest-commits-test-3.git"]
    (helpers/with-bootstrapped-file-sync-storage-service-for-http
      app
      (helpers/jgit-plaintext-config-with-repos
        git-base-dir
        [{:sub-path server-repo-subpath-1}
         {:sub-path server-repo-subpath-2}
         {:sub-path server-repo-subpath-no-commits}])

      (let [client-orig-repo-dir-1 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-1)
            client-orig-repo-dir-2 (helpers/clone-repo-and-push-test-files
                                     server-repo-subpath-2
                                     2)]

        (testing "Validate /latest-commits endpoint"
          (let [response (http-client/get (str
                                            helpers/server-base-url
                                            helpers/default-api-path-prefix
                                            common/latest-commits-sub-path))
                content-type (get-in response [:headers "content-type"])]

            (testing "the endpoint returns JSON"
              (is (.startsWith content-type "application/json")
                  (str
                    "The response's Content-type should be JSON. Reponse: "
                    response)))

            (testing "the SHA-1 IDs it returns are correct"
              (let [body (parse-response-body response)]
                (is (map? body))

                (testing "A repository with no commits in it returns a null ID"
                  (is (contains? body server-repo-subpath-no-commits))
                  (let [rev (get body server-repo-subpath-no-commits)]
                    (is (= rev nil))))

                (testing "the first repo"
                  (let [actual-rev (get body server-repo-subpath-1)
                        expected-rev (client/head-rev-id client-orig-repo-dir-1)]
                    (is (= actual-rev expected-rev))))

                (testing "The second repo"
                  (let [actual-rev (get body server-repo-subpath-2)
                        expected-rev (client/head-rev-id client-orig-repo-dir-2)]
                    (is (= actual-rev expected-rev))))))))))))
