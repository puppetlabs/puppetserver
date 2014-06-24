(ns puppetlabs.master.bootstrap-test
  (:import (java.io IOException)
           (org.httpkit ProtocolException))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.testutils :refer [with-no-jvm-shutdown-hooks]]
            [puppetlabs.master.services.config.jvm-puppet-config-service
              :as jvm-puppet-config-service]
            [puppetlabs.master.services.handler.request-handler-service
              :as request-handler-service]
            [puppetlabs.master.services.jruby.jruby-puppet-service
              :as jruby-puppet-service]
            [puppetlabs.master.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.master.services.master.master-service
              :as master-service]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.internal :as tk-internal]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service
              :as jetty-service]
            [puppetlabs.trapperkeeper.testutils.bootstrap
              :as tk-bootstrap-testutils]
            [puppetlabs.trapperkeeper.testutils.webserver.common
              :as tk-webserver-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]))

(use-fixtures :each logging/reset-logging-config-after-test)

(def dev-config-file
  "./dev-resources/jvm-puppet.conf")

(def dev-bootstrap-file
  "./dev-resources/bootstrap.cfg")

(def jvm-puppet-service-stack
  [jetty-service/jetty9-service
   master-service/master-service
   jvm-puppet-config-service/jvm-puppet-config-service
   jruby-puppet-service/jruby-puppet-pooled-service
   request-handler-service/request-handler-service])

(deftest test-app-startup
  (testing "Trapperkeeper can be booted successfully using the dev config files."
    (with-no-jvm-shutdown-hooks
      (let [config (tk-config/parse-config-path dev-config-file)
            services (tk-bootstrap/parse-bootstrap-config! dev-bootstrap-file)]
        (->
          (tk/build-app services config)
          (tk-internal/throw-app-error-if-exists!))))
    (is (true? true))))

(defn validate-connection-failure
  [f]
  (try
    (f)
    (is false "Connection succeeded but should have failed")
    (catch IOException e)
    (catch ProtocolException e))
  nil)

(deftest test-app-startup-against-crls
  (let [port     8081
        test-url (str "https://localhost:" port "/production/node/localhost")]
    (tk-bootstrap-testutils/with-app-with-config
      app
      jvm-puppet-service-stack
      {:webserver
        {:ssl-host    "0.0.0.0"
         :ssl-port    port
         :client-auth "need"}
       :jruby-puppet (jruby-testutils/jruby-puppet-config-with-prod-env 1)}
      (testing (str "Simple request to jvm puppet succeeds when the client "
                    "certificate's serial number is not in the server's CRL.")
        (let [response
               (tk-webserver-testutils/http-get
                 test-url
                 {:ssl-cert
                   "./dev-resources/config/master/conf/ssl/certs/localhost.pem"
                  :ssl-key
                   "./dev-resources/config/master/conf/ssl/private_keys/localhost.pem"
                  :ssl-ca-cert
                    "./dev-resources/config/master/conf/ssl/certs/ca.pem"
                  :keepalive 0})]
          (is (= (:status response) 200))))
      (testing (str "Simple request to jvm puppet fails when the client "
                    "certificate's serial number is in the server's CRL.")
        (validate-connection-failure
          #(tk-webserver-testutils/http-get
            test-url
            {:ssl-cert
              "./dev-resources/config/master/conf/ssl/certs/localhost-compromised.pem"
             :ssl-key
              "./dev-resources/config/master/conf/ssl/private_keys/localhost-compromised.pem"
             :ssl-ca-cert
              "./dev-resources/config/master/conf/ssl/certs/ca.pem"
             :keepalive 0}))))))