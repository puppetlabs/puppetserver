(ns puppetlabs.services.versioned-code-service.versioned-code-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
    [puppetlabs.services.protocols.versioned-code :as vc]
    [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.logging :as logging]
    [puppetlabs.kitchensink.core :as ks])
  (:import
   (org.apache.commons.io IOUtils)))

(def test-resources
  (ks/absolute-path
   "./dev-resources/puppetlabs/services/versioned_code_service/versioned_code_service_test"))

(defn script-path
  [script-name]
  (str test-resources "/" script-name))

(defn vcs-config
  [script]
  {:versioned-code {:code-id-command script
                    :code-content-command script}})

(deftest test-code-id-execution
  (testing "nil is returned if no code-id-command is set"
    (logging/with-test-logging
     (tk-testutils/with-app-with-config
      app

      [vcs/versioned-code-service]
      {}
      (let [vcs (tk-app/get-service app :VersionedCodeService)]
        (is (nil? (vc/current-code-id vcs "foo")))
        (is (logged? #"No code-id-command set" :info))))))

  (testing "code id is successfully generated"
    (tk-testutils/with-app-with-config
     app

     [vcs/versioned-code-service]
     (vcs-config (script-path "echo"))
     (let [vcs (tk-app/get-service app :VersionedCodeService)]
       (is (= "foo" (vc/current-code-id vcs "foo")))))))

(deftest test-code-content-execution
  (testing "When calling get-code-content"
    (testing "and there is no code-content-command"
      (logging/with-test-logging
        (tk-testutils/with-app-with-config app [vcs/versioned-code-service] {}
          (let [vcs (tk-app/get-service app :VersionedCodeService)]
            (is (thrown-with-msg? IllegalStateException #".*Cannot retrieve code content because the \"versioned-code.code-content-command\" setting is not present in configuration.*"
                                  (vc/get-code-content vcs "test" "foobar" "foo/bar/")))))))
    (testing "and there is a code-content-command"
      (logging/with-test-logging
        (tk-testutils/with-app-with-config
          app
          [vcs/versioned-code-service]
          (vcs-config (script-path "echo"))
          (let [vcs (tk-app/get-service app :VersionedCodeService)
                result (-> (vc/get-code-content vcs "test" "foobar" "foo/bar/")
                           (IOUtils/toString "UTF-8"))]
            (is (= "test foobar foo/bar/\n" result))))))))

(deftest ^:integration vcs-fails-startup-if-misconfigured
  (testing "If only :code-id-command is set, it will not start up"
    (logging/with-test-logging
     (try
       (tk-testutils/with-app-with-config
        app
        [vcs/versioned-code-service]
        {:versioned-code {:code-id-command (script-path "echo")}})
       (catch IllegalStateException e
         (is (re-find #"Only one of .* was set." (.getMessage e)))))))
  (testing "If only :code-content-command is set, it will not start up"
    (logging/with-test-logging
     (try
       (tk-testutils/with-app-with-config
        app
        [vcs/versioned-code-service]
        {:versioned-code {:code-content-command (script-path "echo")}})
       (catch IllegalStateException e
         (is (re-find #"Only one of .* was set." (.getMessage e))))))))