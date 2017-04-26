(ns puppetlabs.puppetserver.error-handling-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.trapperkeeper.testutils.logging :refer :all]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.puppetserver.testutils :as testutils]
    [puppetlabs.puppetserver.certificate-authority :as ca]
    [puppetlabs.services.request-handler.request-handler-core :as request-handler]
    [me.raynes.fs :as fs]
    [puppetlabs.kitchensink.core :as ks]))

(def test-resources "./dev-resources/puppetlabs/puppetserver/error_handling_int_test")
(def vcs-scripts "./dev-resources/puppetlabs/services/versioned_code_service/versioned_code_core_test")

(use-fixtures :once
              (testutils/with-puppet-conf (fs/file test-resources "puppet.conf")))

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
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because the request handlers are being with-redef'd
     to throw exceptions."
      app
      {}
      (with-test-logging
        (testing "the Puppet Master's main API"
          ;; This next line of code is wacky, so here's an explanation -
          ;; this test is specifically written for the case an Exception is
          ;; thrown somewhere inside this codebase, and is not caught anywhere
          ;; until it gets all the way up the stack to jetty.  To trigger this
          ;; sort of error, the next line of code re-defines one of the main
          ;; 'request handling' functions (which is part of the mapping layer
          ;; between the ring handler and the JRuby layer, and called on every
          ;; request) to simply ignore any arguments and just throw an Exception.
          (with-redefs [request-handler/as-jruby-request just-throw-it]
            (let [response (testutils/http-get "puppet/v3/catalog/localhost?environment=production")]
              (is (= 500 (:status response)))
              (is (= "Internal Server Error: java.lang.Exception: barf"
                     (:body response)))
              (is (re-matches #"text/plain;\s*charset=.*"
                              (get-in response [:headers "content-type"]))))))
        (testing "the CA API - in particular, one of the endpoints implemented via liberator"
          ;; Yes, this is weird - see comment above.
          (with-redefs [ca/get-certificate-status throw-npe]
            (let [response (testutils/http-get "puppet-ca/v1/certificate_status/localhost")]
              (is (= 500 (:status response)))
              (is (= "Internal Server Error: java.lang.NullPointerException"
                     (:body response)))
              (is (re-matches #"text/plain;\s*charset=.*"
                              (get-in response [:headers "content-type"]))))))))))

(deftest ^:integration test-invalid-code-id-error
  (let [vcs-script (ks/absolute-path (fs/file vcs-scripts "invalid_code_id"))]
    (testing "Catalog request fails when user provided code-id-command returns invalid code-id"
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because the Clojure code will throw the code-id-command
       error before the request ever makes it to JRuby."
        app
        {:versioned-code {:code-id-command vcs-script
                          :code-content-command vcs-script}}
        (with-test-logging
          (let [response (testutils/http-get "puppet/v3/catalog/localhost?environment=production")]
            (is (= 500 (:status response)))
            (is (re-matches
                  #"Internal Server Error: java.lang.IllegalStateException: Invalid code-id.+"
                  (:body response)))))))))
