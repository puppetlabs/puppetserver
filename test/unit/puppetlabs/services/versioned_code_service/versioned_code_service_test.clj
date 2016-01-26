(ns puppetlabs.services.versioned-code-service.versioned-code-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
    [puppetlabs.services.protocols.versioned-code :as vc]
    [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.logging :as logging]
    [me.raynes.fs :as fs]))

(def test-resources
  (fs/absolute-path
   "./dev-resources/puppetlabs/services/versioned_code_service/versioned_code_service_test"))

(defn script-path
  [script-name]
  (str test-resources "/" script-name))

(defn vcs-config
  [script]
  {:versioned-code {:code-id-command script}})

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