(ns puppetlabs.services.certificate-authority.ca-true-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [clojure.pprint :as pprint]))

(deftest ca-true-test
  (testing "We are validating that a csr with the CA:TRUE extension is rejected for signing by the server"
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet
      {:master-conf-dir "./dev-resources/puppetlabs/ca_true_test/master/conf"}}
      (let
        [response (http-client/put 
                    (str "https://localhost:8140/"
                    "puppet-ca/v1/certificate_status/test_cert_ca_true")
                    { :ssl-cert "./dev-resources/puppetlabs/ca_true_test/master/conf/ssl/ca/ca_crt.pem"
                      :ssl-key "./dev-resources/puppetlabs/ca_true_test/master/conf/ssl/ca/ca_key.pem"
                      :ssl-ca-cert "./dev-resources/puppetlabs/ca_true_test/master/conf/ssl/ca/ca_crt.pem"
                      :as :text
                      :body "{\"desired_state\": \"signed\"}"
                      :headers {"content-type" "application/json"}})]
        (println "response:")
        (is (= 409 (:status response)))
        (is (.startsWith (:body response) "Found extensions"))
        (is (.contains (:body response) "2.5.29.19"))
        (pprint/pprint response)))))
