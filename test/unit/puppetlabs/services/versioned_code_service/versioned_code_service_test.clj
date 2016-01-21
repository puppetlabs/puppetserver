(ns puppetlabs.services.versioned-code-service.versioned-code-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.services.versioned-code-service.versioned-code-service :as vcs]
    [puppetlabs.services.protocols.versioned-code :as vc]
    [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.logging :as logging]
    [me.raynes.fs :as fs]))

(def test-resources (fs/absolute-path "./dev-resources/puppetlabs/common"))

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
       (is (= "foo" (vc/current-code-id vcs "foo"))))))

  (testing "stderr is logged if generated"
    (logging/with-test-logging
     (tk-testutils/with-app-with-config
      app

      [vcs/versioned-code-service]
      (vcs-config (script-path "warn"))
      (let [vcs (tk-app/get-service app :VersionedCodeService)]
        (is (= "" (vc/current-code-id vcs "foo")))
        (is (logged? (format "Error output generated while calculating code id. Command executed: '%s', stderr: '%s'" (script-path "warn") "foo\n")))))))

  (testing "exit-code, stdout and stderr are all logged for non-zero exit"
    (logging/with-test-logging
     (tk-testutils/with-app-with-config
      app

      [vcs/versioned-code-service]
      (vcs-config (script-path "warn_echo_and_error"))
      (let [vcs (tk-app/get-service app :VersionedCodeService)]
        (is (nil? (vc/current-code-id vcs "foo")))
        (is (logged? (format "Non-zero exit code returned while calculating code id. Command executed: '%s', exit-code '%d', stdout: '%s', stderr: '%s'" (script-path "warn_echo_and_error") 1 "foo\n" "foo\n")))))))

  (testing "nil is returned and error logged for exception during execute-command"
    (logging/with-test-logging
     (tk-testutils/with-app-with-config
      app

      [vcs/versioned-code-service]
      (vcs-config "false")
      (let [vcs (tk-app/get-service app :VersionedCodeService)]
        (is (nil? (vc/current-code-id vcs "foo")))
        (is (logged? "Calculating code id generated an error. Command executed: 'false', error generated: 'An absolute path is required, but 'false' is not an absolute path'")))))))