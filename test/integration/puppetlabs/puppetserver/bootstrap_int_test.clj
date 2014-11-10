(ns puppetlabs.puppetserver.bootstrap-int-test
  (:import (java.io IOException)
           (org.apache.http ConnectionClosedException))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.testutils :refer [with-no-jvm-shutdown-hooks]]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap
             :as tk-bootstrap-testutils]
            [puppetlabs.trapperkeeper.testutils.webserver.common
             :as tk-webserver-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :each logging/reset-logging-config-after-test)

(def dev-config-file
  "./dev/puppet-server.conf.sample")

(def dev-bootstrap-file
  "./dev/bootstrap.cfg")

(deftest ^:integration test-app-startup
  (testing "Trapperkeeper can be booted successfully using the dev config files."
    (let [tmp-conf (ks/temp-file "puppet-server" ".conf")]
      (fs/copy dev-config-file tmp-conf)
      (with-no-jvm-shutdown-hooks
        (let [config (tk-config/load-config tmp-conf)
              services (tk-bootstrap/parse-bootstrap-config! dev-bootstrap-file)]
          (->
            (tk/build-app services config)
            (tk-internal/throw-app-error-if-exists!)))))
    (is (true? true))))


(defn validate-connection-failure
  [f]
  (try
    (f)
    (is false "Connection succeeded but should have failed")
    (catch ConnectionClosedException e)
    (catch IOException e
      (if-not (= (.getMessage e) "Connection reset by peer")
        (throw e))))
  nil)