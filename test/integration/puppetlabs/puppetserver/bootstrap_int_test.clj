(ns puppetlabs.puppetserver.bootstrap-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [me.raynes.fs :as fs]))

(use-fixtures :each logging/reset-logging-config-after-test)

(deftest ^:integration test-app-startup
  (testing "Trapperkeeper can be booted successfully using the dev config files."
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because we're just testing service startup
     and CA file generation (which is all Clojure)."
     app {}
     (is (true? true))
     (testing "Private keys have the correct permissions."
       (let [pk-dir (str bootstrap/master-conf-dir "/ssl/private_keys")
             pks (fs/find-files pk-dir #".*pem$")]
         (is (= ca/private-key-dir-perms (ca/get-file-perms pk-dir)))
         (is (= ca/private-key-perms
                (ca/get-file-perms (str bootstrap/master-conf-dir
                                        "/ssl/ca/ca_key.pem"))))
         (doseq [pk pks]
           (is (= ca/private-key-perms (ca/get-file-perms (.getPath pk)))))))
     (is (true? true)))))
