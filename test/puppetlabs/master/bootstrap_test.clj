(ns puppetlabs.master.bootstrap-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.kitchensink.testutils :refer [with-no-jvm-shutdown-hooks]]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(use-fixtures :each logging/reset-logging-config-after-test)

(def dev-config-file
  "./test-resources/jvm-puppet.conf")

(def dev-bootstrap-file
  "./test-resources/bootstrap.cfg")

(deftest test-app-startup
  (testing "Trapperkeeper can be booted successfully using the dev config files."
    (with-no-jvm-shutdown-hooks
      (let [config (tk-config/parse-config-path dev-config-file)
            services (tk-bootstrap/parse-bootstrap-config! dev-bootstrap-file)]
        (->
          (tk/build-app services config)
          (tk-internal/throw-app-error-if-exists!))))
    (is (true? true))))
