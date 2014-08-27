(ns puppetlabs.services.ca.certificate-authority-core-test
  (:require [puppetlabs.services.ca.certificate-authority-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(def cadir "./dev-resources/config/master/conf/ssl/ca")
(def csrdir (str cadir "/requests"))
(def signeddir (str cadir "/signed"))

(def settings
  {:allow-duplicate-certs true
   :autosign              true
   :ca-name               "some CA"
   :cacert                (str cadir "/ca_crt.pem")
   :cacrl                 (str cadir "/ca_crl.pem")
   :cakey                 (str cadir "/ca_key.pem")
   :capub                 (str cadir "/ca_pub.pem")
   :signeddir             signeddir
   :csrdir                csrdir
   :ca-ttl                100
   :serial                (doto (str (ks/temp-file))
                            (ca/initialize-serial-file!))
   :cert-inventory        (str (ks/temp-file))
   :ruby-load-path        ["ruby/puppet/lib" "ruby/facter/lib"]})

(deftest crl-endpoint-test
  (testing "implementation of the CRL endpoint"
    (let [response (handle-get-certificate-revocation-list
                     {:cacrl "./dev-resources/config/master/conf/ssl/crl.pem"})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response))))))

(deftest puppet-version-header-test
  (testing "Responses contain a X-Puppet-Version header"
    (let [version-number "42.42.42"
          ring-app (compojure-app settings version-number)
          ; we can just GET the /CRL endpoint, so that's an easy test here.
          request (mock/request :get
                                "/production/certificate_revocation_list/mynode")
          response (ring-app request)]
      (is (= version-number (get-in response [:headers "X-Puppet-Version"]))))))

(deftest handle-put-certificate-request!-test
  (let [csr-path  (ca/path-to-cert-request csrdir "test-agent")]
    (testing "when autosign results in true"
      (doseq [value [true
                     "dev-resources/config/master/conf/ruby-autosign-executable"
                     "dev-resources/config/master/conf/autosign-whitelist.conf"]]
        (let [settings      (assoc settings :autosign value)
              csr-stream    (io/input-stream csr-path)
              expected-path (ca/path-to-cert signeddir "test-agent")]

          (testing "it signs the CSR, writes the certificate to disk, and
                    returns a 200 response with empty plaintext body"
            (try
              (is (false? (fs/exists? expected-path)))
              (logutils/with-test-logging
                (let [response (handle-put-certificate-request! "test-agent" csr-stream settings)]
                  (is (true? (fs/exists? expected-path)))
                  (is (= 200 (:status response)))
                  (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                  (is (nil? (:body response)))))
              (finally
                (fs/delete expected-path)))))))

    (testing "when autosign results in false"
      (doseq [value [false
                     "dev-resources/config/master/conf/ruby-autosign-executable-false"
                     "dev-resources/config/master/conf/autosign-whitelist.conf"]]
        (let [settings      (assoc settings :autosign value)
              csr-stream    (io/input-stream "./dev-resources/foo-agent-csr.pem")
              expected-path (ca/path-to-cert-request csrdir "foo-agent")]

          (testing "it writes the CSR to disk and returns a
                    200 response with empty plaintext body"
            (try
              (is (false? (fs/exists? expected-path)))
              (logutils/with-test-logging
                (let [response (handle-put-certificate-request! "foo-agent" csr-stream settings)]
                  (is (true? (fs/exists? expected-path)))
                  (is (false? (fs/exists? (ca/path-to-cert signeddir "foo-agent"))))
                  (is (= 200 (:status response)))
                  (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                  (is (nil? (:body response)))))
              (finally
                (fs/delete expected-path)))))))

    (testing "when $allow-duplicate-certs is false and we receive a new CSR,
              return a 400 response and error message"
      (logutils/with-test-logging
        (let [settings   (assoc settings :allow-duplicate-certs false)
              csr-stream (io/input-stream csr-path)
              response   (handle-put-certificate-request! "test-agent" csr-stream settings)]
          (is (logged? #"ignoring certificate request" :error))
          (is (= 400 (:status response)))
          (is (true? (.contains (:body response) "ignoring certificate request"))))))

    (testing "when the subject CN on a CSR does not match the hostname specified
            in the URL, the response is a 400"
      (let [csr-stream (io/input-stream csr-path)]
        (let [response (handle-put-certificate-request!
                         "NOT-test-agent" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (re-matches
                #"Instance name \"test-agent\" does not match requested key \"NOT-test-agent\""
                (:body response))))))

    (testing "when the public key on the CSR is bogus, the repsonse is a 400"
      (let [csr-with-bad-public-key "dev-resources/luke.madstop.com-bad-public-key.pem"
            csr-stream (io/input-stream csr-with-bad-public-key)]
        (let [response (handle-put-certificate-request!
                         "luke.madstop.com" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "CSR contains a public key that does not correspond to the signing key"
                 (:body response))))))

    (testing "when the CSR has disallowed extensions on it, the repsonse is a 400"
      (let [csr-with-bad-ext "dev-resources/meow-bad-extension.pem"
            csr-stream (io/input-stream csr-with-bad-ext)]
        (let [response (handle-put-certificate-request!
                         "meow" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "Found extensions that are not permitted: 1.9.9.9.9.9.9"
                 (:body response)))))

      (let [csr-with-bad-ext "dev-resources/woof-bad-extensions.pem"
            csr-stream (io/input-stream csr-with-bad-ext)]
        (let [response (handle-put-certificate-request!
                         "woof" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "Found extensions that are not permitted: 1.9.9.9.9.9.0, 1.9.9.9.9.9.1"
                 (:body response))))))

    (testing "when the CSR subject contains invalid characters,
              the response is a 400"

      ; These test cases are lifted out of the puppet spec tests.
      (let [bad-csrs #{{:subject "super/bad"
                        :csr     "dev-resources/bad-subject-name-1.pem"}

                       {:subject "not\neven\tkind\rof"
                        :csr     "dev-resources/bad-subject-name-2.pem"}

                       {:subject "hidden\b\b\b\b\b\bmessage"
                        :csr     "dev-resources/bad-subject-name-3.pem"}}]

        (doseq [{:keys [subject csr]} bad-csrs]
          (let [csr-stream (io/input-stream csr)
                response (handle-put-certificate-request!
                           subject csr-stream settings)]
            (is (= 400 (:status response)))
            (is (= "Subject contains unprintable or non-ASCII characters"
                   (:body response))))))

      (testing "no wildcards allowed"
        (let [csr-with-wildcard "dev-resources/bad-subject-name-wildcard.pem"
              csr-stream (io/input-stream csr-with-wildcard)]
          (let [response (handle-put-certificate-request!
                           "foo*bar" csr-stream settings)]
            (is (= 400 (:status response)))
            (is (= "Subject contains a wildcard, which is not allowed: foo*bar"
                   (:body response)))))))))

(def test-compojure-app
  (compojure-app settings "42.42.42"))

(defn body-stream
  [s]
  (io/input-stream (.getBytes s)))

(deftest certificate-status-test

  (testing "The /certificate_status endpoint"
    (testing "GET"
      (let [request {:uri "/production/certificate_status/myagent"
                     :request-method :get
                     :content-type "application/json"}
             response (test-compojure-app request)
             expected-response-body {:msg      "you called get-certificate-status.  hi!"
                                     :certname "myagent"}]
        (is (= 200 (:status response)))
        (is (= expected-response-body (json/parse-string (:body response) true)))))

    (testing "PUT"
      (testing "signing a cert"
        (let [request {:uri "/production/certificate_status/myagent"
                       :request-method :put
                       :content-type "application/json"
                       :body (body-stream "{\"desired_state\":\"signed\"}")}
              response (test-compojure-app request)]
          (is (= 204 (:status response))
              (str "response was: " response))))

      (testing "revoking a cert"
        (let [request {:uri "/production/certificate_status/myagent"
                       :request-method :put
                       :content-type "application/json"
                       :body (body-stream "{\"desired_state\":\"revoked\"}")}
              response (test-compojure-app request)]
          (is (= 204 (:status response))
              (str "response was: " response))))

      (testing "no body results in a 400"
        (let [request {:uri "/production/certificate_status/myagent"
                       :request-method :put
                       :content-type "application/json"}
              response (test-compojure-app request)]
          (is (= 400 (:status response))
              (str "response was: " response))))

      (testing "invalid cert status results in a 400"
        (let [request {:uri "/production/certificate_status/myagent"
                       :request-method :put
                       :content-type "application/json"
                       :body (body-stream "{\"desired_state\":\"bogus\"}")}
              response (test-compojure-app request)]
          (is (= 400 (:status response))
              (str "response was: " response)))))

    (testing "DELETE"
      (let [request {:uri "/production/certificate_status/myagent"
                     :request-method :delete
                     :content-type "application/json"}
             response (test-compojure-app request)]
        (is (= 204 (:status response)))))

    (testing "returns a 501 when responding to a request without a 'Content-Type' header"
      (let [request {:uri "/production/certificate_status/myagent"}
            response (test-compojure-app request)]
        (is (= 501 (:status response)))))

    ; TODO - fix this after 'certificate-exists?' is implemented
    (testing "returns a 404 when a non-existent certname is given"
      (let [request {:uri "/production/certificate_status/doesnotexist"
                     :request-method :get
                     :content-type "application/json"}
            response (test-compojure-app request)]
        (is (= 404 (:status response))
            (str "response was: " response)))))

  (testing "GET to /certificate_statuses"
      (let [response (test-compojure-app
                       {:uri "/production/certificate_statuses/thisisirrelevant"
                        :request-method :get
                        :content-type "application/json"})
            expected-response-body {:msg "you called get-certificate-statuses.  hi!"}]
        (is (= 200 (:status response))
            (str "response was: " response))
        (is (= expected-response-body (json/parse-string (:body response) true))))))
