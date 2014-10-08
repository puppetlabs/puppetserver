(ns puppetlabs.services.request-handler.request-handler-core-test
  (:import (java.io StringReader ByteArrayInputStream))
  (:require [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.certificate-authority.core :as cert-utils]
            [clojure.test :refer :all]))

(defn puppet-server-config
  [allow-header-certs]
  (core/config->request-handler-settings
    {:puppet-server {:ssl-client-verify-header "HTTP_X_CLIENT_VERIFY"
                     :ssl-client-header        "HTTP_X_CLIENT_DN"}
     :global        {:allow-header-cert-info allow-header-certs}}))

(deftest get-cert-common-name-test
  (testing (str "expected common name can be extracted from the certificate on "
                "a request")
    (let [cert    (-> "./dev-resources/puppetlabs/services/request_handler/request_handler_core_test/localhost.pem"
                      cert-utils/pem->certs
                      first)
          request {:ssl-client-cert cert}]
      (is (= "localhost" (core/get-cert-common-name request)))))
  (testing "nil returned for cn when no certificate on request"
    (is (nil? (core/get-cert-common-name {}))))
  (testing "nil returned for cn when certificate on request is nil"
    (is (nil? (core/get-cert-common-name {:ssl-client-cert nil})))))

(deftest wrap-params-for-jruby-test
  (testing "get with no query parameters returns empty params"
    (let [wrapped-request (core/wrap-params-for-jruby
                            {:body (StringReader. "")})]
      (is (= {} (:params wrapped-request))
          "Unexpected params in wrapped request")
      (is (= "" (:body-string wrapped-request))
          "Unexpected body string in wrapped request")))
  (testing "get with query parameters returns expected values"
    (let [wrapped-request (core/wrap-params-for-jruby
                            {:body         (StringReader. "")
                             :query-string "one=1%201&two=2&arr[]=3&arr[]=4"
                             :params       {:bogus ""}})]
      (is (= {"one" "1 1", "two" "2", "arr[]" ["3", "4"]}
             (:params wrapped-request))
          "Unexpected params in wrapped request")
      (is (= "" (:body-string wrapped-request))
          "Unexpected body string in wrapped request")))
  (testing "post with form parameters returns expected values"
    (let [body-string     "one=1&two=2%202&arr[]=3&arr[]=4"
          wrapped-request (core/wrap-params-for-jruby
                            {:body (StringReader. body-string)
                             :content-type "application/x-www-form-urlencoded"
                             :params       {:bogus ""}})]
      (is (= {"one" "1", "two" "2 2", "arr[]" ["3" "4"]}
             (:params wrapped-request))
          "Unexpected params in wrapped request")
      (is (= body-string (:body-string wrapped-request))
          "Unexpected body string in wrapped request")))
  (testing "post with plain text in default encoding returns expected values"
    (let [body-string     "some random text"
          wrapped-request (core/wrap-params-for-jruby
                            {:body (StringReader. body-string)
                             :content-type "plain/text"
                             :params       {:bogus ""}})]
      (is (= {} (:params wrapped-request))
          "Unexpected params in wrapped request")
      (is (= body-string (:body-string wrapped-request))
          "Unexpected body string in wrapped request")))
  (testing "post with plain text in UTF-16 returns expected values"
    (let [body-string-from-utf16 (String. (.getBytes
                                            "some random text from utf-16"
                                            "UTF-16"))
          wrapped-request        (core/wrap-params-for-jruby
                                   {:body                (ByteArrayInputStream.
                                                           (.getBytes
                                                             body-string-from-utf16
                                                             "UTF-16"))
                                    :content-type        "plain/text"
                                    :character-encoding  "UTF-16"
                                    :params              {:bogus ""}})]
      (is (= {} (:params wrapped-request))
          "Unexpected params in wrapped request")
      (is (= body-string-from-utf16 (:body-string wrapped-request))
          "Unexpected body string in wrapped request"))))

(deftest unmunge-header-name-works
  (testing "Umunging a puppet.conf http header named works as expected"
    (is (= (core/unmunge-http-header-name "HTTP_X_CLIENT_VERIFY")
           "x-client-verify"))
    (is (= (core/unmunge-http-header-name "HTTP_X_CLIENT_DN")
           "x-client-dn"))))

(deftest cert-info-in-headers
  "In the case where Puppet Server is running under HTTP with an upstream HTTPS
  terminator, the cert's CN and authenticated status will be provided as HTTP
  headers."
  (testing "providing headers but not the pupper server config won't work."
    (let [req (core/as-jruby-request
                (puppet-server-config false)
                {:request-method :GET
                 :headers        {"x-client-verify" "SUCCESS"
                                  "x-client-dn"     "CN=puppet"}})]
      (is (not  (get req :authenticated)))
      (is (nil? (get req :client-cert-cn)))
      (is (nil? (get req :client-cer)))))

  (testing "providing headers and allow-header-cert-info to true works"
    (let [req (core/as-jruby-request
                (puppet-server-config true)
                {:request-method :GET
                 :headers        {"x-client-verify" "SUCCESS"
                                  "x-client-dn"     "CN=puppet"}})]
      (is (get req :authenticated))
      (is (= "puppet" (get req :client-cert-cn)))
      (is (nil? (get req :client-cert)))))

  (testing "a malformed DN string fails"
    (is (thrown? AssertionError
                 (core/as-jruby-request
                   (puppet-server-config true)
                   {:request-method :GET
                    :headers        {"x-client-verify" "SUCCESS"
                                     "x-client-dn"     "invalid-dn"}}))))

  (testing "Setting the auth header to somethign other than 'SUCCESS' fails"
    (let [req (core/as-jruby-request
                (puppet-server-config true)
                {:request-method :GET
                 :headers        {"x-client-verify" "fail"
                                  "x-client-dn"     "CN=puppet"}})]
      (is (not (get req :authenticated)))
      (is (= "puppet" (get req :client-cert-cn)))
      (is (nil? (get req :client-cert))))))