(ns puppetlabs.services.request-handler.request-handler-core-test
  (:import (java.io StringReader ByteArrayInputStream))
  (:require [puppetlabs.services.request-handler.request-handler-core :as core]
            [puppetlabs.certificate-authority.core :as cert-utils]
            [puppetlabs.puppetserver.certificate-authority :as cert-authority]
            [clojure.test :refer :all]
            [ring.util.codec :as ring-codec]
            [slingshot.slingshot :as sling]
            [slingshot.test :refer :all]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test Data

(def test-resources-dir (str "./dev-resources/puppetlabs/services/"
                             "request_handler/request_handler_core_test"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn puppet-server-config
  [allow-header-certs]
  (core/config->request-handler-settings
    {:puppet-server {:ssl-client-verify-header "HTTP_X_CLIENT_VERIFY"
                     :ssl-client-header        "HTTP_X_CLIENT_DN"}
     :master        {:allow-header-cert-info allow-header-certs}}))

(defn jruby-request-with-client-cert-header
  [cert]
  (core/as-jruby-request
    (puppet-server-config true)
    {:request-method :GET
     :headers {"x-client-cert" cert}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest get-cert-common-name-test
  (testing (str "expected common name can be extracted from the certificate on "
                "a request")
    (let [cert (cert-utils/pem->cert
                 (str test-resources-dir "/localhost.pem"))]
      (is (= "localhost" (core/get-cert-common-name cert)))))
  (testing "nil returned for cn when certificate on request is nil"
    (is (nil? (core/get-cert-common-name nil)))))

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
    (let [body-string "one=1&two=2%202&arr[]=3&arr[]=4"
          wrapped-request (core/wrap-params-for-jruby
                            {:body         (StringReader. body-string)
                             :content-type "application/x-www-form-urlencoded"
                             :params       {:bogus ""}})]
      (is (= {"one" "1", "two" "2 2", "arr[]" ["3" "4"]}
             (:params wrapped-request))
          "Unexpected params in wrapped request")
      (is (= body-string (:body-string wrapped-request))
          "Unexpected body string in wrapped request")))
  (testing "post with plain text in default encoding returns expected values"
    (let [body-string "some random text"
          wrapped-request (core/wrap-params-for-jruby
                            {:body         (StringReader. body-string)
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
          wrapped-request (core/wrap-params-for-jruby
                            {:body               (ByteArrayInputStream.
                                                   (.getBytes
                                                     body-string-from-utf16
                                                     "UTF-16"))
                             :content-type       "plain/text"
                             :character-encoding "UTF-16"
                             :params             {:bogus ""}})]
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
  terminator, the cert's CN, cert, and authenticated status will be provided as
  HTTP headers.  If cert info is not provided in the headers but is available
  via SSL, the SSL info will be used."
  (let [single-cert-url-encoded (-> (str test-resources-dir "/localhost.pem")
                                    slurp
                                    ring-codec/url-encode)
        second-cert-url-encoded (-> (str test-resources-dir "/master.pem")
                                    slurp
                                    ring-codec/url-encode)]

    (testing "providing headers but not the puppet server config won't work."
      (let [req (core/as-jruby-request
                  (puppet-server-config false)
                  {:request-method :GET
                   :headers        {"x-client-verify" "SUCCESS"
                                    "x-client-dn"     "CN=puppet"
                                    "x-client-cert"   single-cert-url-encoded}})]
        (is (not (get req :authenticated)))
        (is (nil? (get req :client-cert-cn)))
        (is (nil? (get req :client-cert)))))

    (testing "providing headers and allow-header-cert-info to true works"
      (let [req (core/as-jruby-request
                  (puppet-server-config true)
                  {:request-method :GET
                   :headers        {"x-client-verify" "SUCCESS"
                                    "x-client-dn"     "CN=puppet"
                                    "x-client-cert"   single-cert-url-encoded}})]
        (is (get req :authenticated))
        (is (= "puppet" (get req :client-cert-cn)))
        (is (= "CN=localhost"
               (cert-authority/get-subject (get req :client-cert))))))

    (testing "a malformed DN string fails"
      (let [req (core/as-jruby-request
                  (puppet-server-config true)
                  {:request-method :GET
                   :headers        {"x-client-verify" "SUCCESS"
                                    "x-client-dn"     "invalid-dn"}})]
        (is (not (get req :authenticated)))
        (is (nil? (get req :client-cert)))
        (is (nil? (get req :client-cert-cn)))))

    (testing "Setting the auth header to something other than 'SUCCESS' fails"
      (let [req (core/as-jruby-request
                  (puppet-server-config true)
                  {:request-method :GET
                   :headers        {"x-client-verify" "fail"
                                    "x-client-dn"     "CN=puppet"}})]
        (is (not (get req :authenticated)))
        (is (= "puppet" (get req :client-cert-cn)))
        (is (nil? (get req :client-cert)))))

    (testing "cert and cn from header used and not from SSL cert when allow-header-cert-info true"
      (let [cert (cert-utils/pem->cert
                   (str test-resources-dir "/localhost.pem"))
            req (core/as-jruby-request
                  (puppet-server-config true)
                  {:request-method  :GET
                   :ssl-client-cert cert
                   :headers         {"x-client-verify" "SUCCESS"
                                     "x-client-dn"     "CN=puppet"
                                     "x-client-cert"    second-cert-url-encoded}})]
        (is (get req :authenticated))
        (is (= "puppet" (get req :client-cert-cn)))
        (is (= "CN=master1.example.org"
               (cert-authority/get-subject (get req :client-cert))))))

    (testing "cert and cn from ssl used when allow-header-cert-info false"
      (let [cert (cert-utils/pem->cert
                   (str test-resources-dir "/localhost.pem"))
            req (core/as-jruby-request
                  (puppet-server-config false)
                  {:request-method  :GET
                   :ssl-client-cert cert
                   :headers         {"x-client-verify" "SUCCESS"
                                     "x-client-dn"     "CN=puppet"
                                     "x-client-cert"   second-cert-url-encoded}})]
        (is (get req :authenticated))
        (is (= "localhost" (get req :client-cert-cn)))
        (is (identical? cert (get req :client-cert)))))))

(deftest cert-decoding-failures
  "A cert provided in the x-client-cert header that cannot be decoded into
  an X509Certificate object throws the expected failure"
  (testing "Improperly URL encoded content"
    (is (thrown+? [:type    :puppetlabs.services.request-handler.request-handler-core/bad-request
                   :message (str "Unable to URL decode the x-client-cert header: "
                                 "For input string: \"1%\"")]
                  (jruby-request-with-client-cert-header "%1%2"))))
  (testing "Bad certificate content"
    (is (thrown+? [:type    :puppetlabs.services.request-handler.request-handler-core/bad-request
                   :message (str "Unable to parse x-client-cert into "
                                 "certificate: -----END CERTIFICATE not found")]
                  (jruby-request-with-client-cert-header
                    "-----BEGIN%20CERTIFICATE-----%0AM"))))
  (testing "No certificate in content"
    (is (thrown+? [:type    :puppetlabs.services.request-handler.request-handler-core/bad-request
                   :message "No certs found in PEM read from x-client-cert"]
                  (jruby-request-with-client-cert-header
                    "NOCERTSHERE"))))
  (testing "More than 1 certificate in content"
    (is (thrown+? [:type    :puppetlabs.services.request-handler.request-handler-core/bad-request
                   :message "Only 1 PEM should be supplied for x-client-cert but 3 found"]
                  (jruby-request-with-client-cert-header
                    (-> (str test-resources-dir "/master-with-all-cas.pem")
                        slurp
                        ring-codec/url-encode))))))

(deftest handle-request-test
  (testing "slingshot bad requests translated to ring response"
    (let [bad-message "it's real bad"]
      (with-redefs [core/as-jruby-request (fn [_ _]
                                            (core/throw-bad-request!
                                              bad-message))]
        (let [response (core/handle-request {:body (StringReader. "blah")}
                                            {}
                                            {})]
          (is (= 400 (:status response)) "Unexpected response status")
          (is (= bad-message (:body response)) "Unexpected response body"))))))