(ns puppetlabs.puppetserver.error-handling-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.trapperkeeper.testutils.logging :refer :all]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.puppetserver.certificate-authority :as ca]
    [puppetlabs.services.request-handler.request-handler-core :as request-handler]
    [puppetlabs.services.version.version-check-core :as version-check]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.services.config.puppet-server-config-core :as config]
    [puppetlabs.services.master.master-core :as master]))

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
                "./dev-resources/puppetlabs/puppetserver/error_handling_int_test/puppet.conf"))

;; Used in the test below.
(defn just-throw-it
  [& _]
  (throw (Exception. "barf")))

(defn throw-npe
  [& _]
  (throw (NullPointerException.)))

(deftest ^:integration test-HTTP-500
  (testing "When returning an HTTP 500, the response body is a simple text message,
            not anything wacky like HTML (I'm looking at you, Jetty.)"
    (bootstrap/with-puppetserver-running
      app
      {:certificate-authority {:certificate-status {:authorization-required false}}}
      (with-test-logging
        (testing "the Puppet Master's main API"
          ;; This next line of code is wacky, so here's an explanation -
          ;; this test is specifically written for the case an Exception is
          ;; thrown somewhere inside this codebase, and is not caught anywhere
          ;; until it gets all the way up the stack to jetty.  To trigger this
          ;; sort of error, the next line of code re-defines the main
          ;; 'request handling' function (which is part of the mapping layer
          ;; between the ring handler and the JRuby layer, and called on every
          ;; request) to simply ignore any arguments and just throw an Exception.
          (with-redefs [request-handler/handle-request just-throw-it]
            (let [response (http-client/get
                             "https://localhost:8140/puppet/v3/catalog/localhost?environment=production"
                             bootstrap/request-options)]
              (is (= 500 (:status response)))
              (is (= "Internal Server Error: java.lang.Exception: barf"
                     (:body response)))
              (is (re-matches #"text/plain; charset=.*"
                              (get-in response [:headers "content-type"]))))))
        (testing "the CA API - in particular, one of the endpoints implemented via liberator"
          ;; Yes, this is weird - see comment above.
          (with-redefs [ca/get-certificate-status throw-npe]
            (let [response (http-client/get
                             "https://localhost:8140/puppet-ca/v1/certificate_status/localhost"
                             bootstrap/request-options)]
              (is (= 500 (:status response)))
              (is (= "Internal Server Error: java.lang.NullPointerException"
                     (:body response)))
              (is (re-matches #"text/plain; charset=.*"
                              (get-in response [:headers "content-type"]))))))))))

(deftest ^:integration test-HTTP-404
  (testing "When an improperly-formatted request is made (i.e., in the pre-Puppet 4 format),
            a 404 error is logged containing information on upgrading the agent."
    (bootstrap/with-puppetserver-running
      app
      {:certificate-authority {:certificate-status {:authorization-required false}}}
      (with-test-logging
        (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
              puppet-server-version-regex (re-pattern (str "Puppet Server version: "
                                                           (version-check/get-version-string "puppet-server")))
              puppet-version-regex (re-pattern (str "Puppet version: " (:puppet-version (config/get-puppet-config jruby-service))))
              puppet-api-regex (re-pattern (str "Supported /puppet API versions: " master/puppet-API-versions))
              puppet-ca-api-regex (re-pattern (str "Supported /puppet-ca API versions: " master/puppet-ca-API-versions))]
          (testing "Puppet 3 Master URLs are no longer valid"
            (let [response (http-client/get
                             "https://localhost:8140/production/catalog/localhost"
                             bootstrap/request-options)]
              (is (= 404 (:status response)))
              (is (re-find puppet-server-version-regex (:body response)))
              (is (re-find puppet-version-regex (:body response)))
              (is (re-find puppet-api-regex (:body response)))
              (is (re-find puppet-ca-api-regex (:body response)))))

          (testing "Puppet 3 CA URLs are no longer valid"
            (let [response (http-client/get
                             "https://localhost:8140/production/certificate_status/localhost"
                             bootstrap/request-options)]
              (is (= 404 (:status response)))
              (is (re-find puppet-server-version-regex (:body response)))
              (is (re-find puppet-version-regex (:body response)))
              (is (re-find puppet-api-regex (:body response)))
              (is (re-find puppet-ca-api-regex (:body response)))))

          (testing "URLs with the /puppet prefix will not get the 404 upgrade message"
            (let [response (http-client/get
                             "https://localhost:8140/puppet/unknown"
                             bootstrap/request-options)]
              (is (= 404 (:status response)))
              (is (nil? (re-find puppet-server-version-regex (:body response))))))

          (testing "URLs with the /puppet-ca prefix will not get the 404 upgrade message"
            (let [response (http-client/get
                             "https://localhost:8140/puppet-ca/unknown"
                             bootstrap/request-options)]
              (is (= 404 (:status response)))
              (is (nil? (re-find puppet-server-version-regex (:body response)))))))))))
