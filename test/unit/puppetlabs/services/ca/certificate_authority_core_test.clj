(ns puppetlabs.services.ca.certificate-authority-core-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.certificate-authority.core :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.ca.ca-testutils :as testutils]
            [puppetlabs.services.ca.certificate-authority-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [ring.mock.request :as mock]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def test-resources-dir "./dev-resources/puppetlabs/services/ca/certificate_authority_core_test")
(def cadir (str test-resources-dir "/master/conf/ssl/ca"))
(def csrdir (str cadir "/requests"))
(def test-pems-dir (str test-resources-dir "/pems"))
(def autosign-files-dir (str test-resources-dir "/autosign"))

(defn test-pem-file
  [pem-file-name]
  (str test-pems-dir "/" pem-file-name))

(defn test-autosign-file
   [autosign-file-name]
   (str autosign-files-dir "/" autosign-file-name))

(def localhost-cert
  (utils/pem->cert (test-pem-file "localhost-cert.pem")))

(defn wrap-with-ssl-client-cert
  "Wrap a compojure app so all requests will include the
   localhost certificate to allow access to the certificate
   status endpoint."
  [app]
  (fn [request]
    (-> request
        (assoc :ssl-client-cert localhost-cert)
        (app))))

(defn body-stream
  [s]
  (io/input-stream (.getBytes s)))

(def localhost-status
  {:dns_alt_names ["DNS:djroomba.vpn.puppetlabs.net"
                   "DNS:localhost"
                   "DNS:puppet"
                   "DNS:puppet.vpn.puppetlabs.net"]
   :fingerprint   "F3:12:6C:81:AC:14:03:8D:63:37:82:E4:C4:1D:21:91:55:7E:88:67:9F:EA:BD:2B:BF:1A:02:96:CE:F8:1C:73"
   :fingerprints  {:SHA1 "DB:32:CD:AB:88:86:E0:64:0A:B7:5B:88:76:E4:60:3A:CD:9E:36:C1"
                   :SHA256 "F3:12:6C:81:AC:14:03:8D:63:37:82:E4:C4:1D:21:91:55:7E:88:67:9F:EA:BD:2B:BF:1A:02:96:CE:F8:1C:73"
                   :SHA512 "58:22:32:60:CE:E7:E9:C9:CB:6A:01:52:81:ED:24:D4:69:8E:9E:CF:D8:A7:4E:6E:B5:C7:E7:18:59:5F:81:4C:93:11:77:E6:F0:40:70:5B:9C:9D:BE:22:A6:61:0B:F9:46:70:43:09:58:7E:6B:B7:5B:D9:6A:54:36:09:53:F9"
                   :default "F3:12:6C:81:AC:14:03:8D:63:37:82:E4:C4:1D:21:91:55:7E:88:67:9F:EA:BD:2B:BF:1A:02:96:CE:F8:1C:73"}
   :name          "localhost"
   :state         "signed"})

(def test-agent-status
  {:dns_alt_names []
   :fingerprint   "36:94:27:47:EA:51:EE:7C:43:D2:EC:24:24:BB:85:CD:4A:D1:FB:BB:09:27:D9:61:59:D0:07:94:2B:2F:56:E3"
   :fingerprints  {:SHA1 "EB:3D:7B:9C:85:3E:56:7A:3E:9D:1B:C4:7A:21:5A:91:F5:00:4D:9D"
                   :SHA256 "36:94:27:47:EA:51:EE:7C:43:D2:EC:24:24:BB:85:CD:4A:D1:FB:BB:09:27:D9:61:59:D0:07:94:2B:2F:56:E3"
                   :SHA512 "80:C5:EE:B7:A5:FB:5E:53:7C:51:3A:A0:78:AF:CD:E3:7C:BA:B1:D6:BB:BD:61:9E:A0:2E:D2:12:3C:D8:6E:8D:86:7C:FC:FB:4C:6B:1D:15:63:02:19:D2:F8:49:7D:1A:11:78:07:31:23:22:36:61:0C:D8:E9:F4:97:0B:67:47"
                   :default "36:94:27:47:EA:51:EE:7C:43:D2:EC:24:24:BB:85:CD:4A:D1:FB:BB:09:27:D9:61:59:D0:07:94:2B:2F:56:E3"}
   :name          "test-agent"
   :state         "requested"})

(def revoked-agent-status
  {:dns_alt_names ["DNS:BAR"
                   "DNS:Baz4"
                   "DNS:foo"
                   "DNS:revoked-agent"]
   :fingerprint   "1C:D0:29:04:9B:49:F5:ED:AB:E9:85:CC:D9:6F:20:E1:7F:84:06:8A:1D:37:19:ED:EA:24:66:C6:6E:D4:6D:95"
   :fingerprints  {:SHA1 "38:56:67:FF:20:91:0E:85:C4:DF:CA:16:77:60:D2:BB:FB:DF:68:BB"
                   :SHA256 "1C:D0:29:04:9B:49:F5:ED:AB:E9:85:CC:D9:6F:20:E1:7F:84:06:8A:1D:37:19:ED:EA:24:66:C6:6E:D4:6D:95"
                   :SHA512 "1A:E3:12:14:81:50:38:19:3C:C6:42:4B:BB:09:16:0C:B1:8A:3C:EB:8C:64:9C:88:46:C6:7E:35:5E:11:0C:7A:CC:B2:47:A2:EB:57:63:5C:48:68:22:57:62:A1:46:64:B4:56:29:47:A5:46:F4:BD:9B:45:77:19:91:0B:35:39"
                   :default "1C:D0:29:04:9B:49:F5:ED:AB:E9:85:CC:D9:6F:20:E1:7F:84:06:8A:1D:37:19:ED:EA:24:66:C6:6E:D4:6D:95"}
   :name          "revoked-agent"
   :state         "revoked"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest crl-endpoint-test
  (testing "implementation of the CRL endpoint"
    (let [response (handle-get-certificate-revocation-list
                     {:cacrl (test-pem-file "crl.pem")})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response))))))

(deftest puppet-version-header-test
  (testing "Responses contain a X-Puppet-Version header"
    (let [version-number "42.42.42"
          ring-app (compojure-app (testutils/ca-settings cadir) version-number)
          ;; we can just GET the /CRL endpoint, so that's an easy test here.
          request (mock/request :get
                                "/production/certificate_revocation_list/mynode")
          response (ring-app request)]
      (is (= version-number (get-in response [:headers "X-Puppet-Version"]))))))

(deftest handle-put-certificate-request!-test
  (let [settings   (assoc (testutils/ca-sandbox! cadir)
                     :allow-duplicate-certs true)
        static-csr (ca/path-to-cert-request (str cadir "/requests") "test-agent")]
    (logutils/with-test-logging
      (testing "when autosign results in true"
        (doseq [value [true
                       (test-autosign-file "ruby-autosign-executable")
                       (test-autosign-file "autosign-whitelist.conf")]]
          (let [settings      (assoc settings :autosign value)
                csr-stream    (io/input-stream static-csr)
                expected-path (ca/path-to-cert (:signeddir settings) "test-agent")]

            (testing "it signs the CSR, writes the certificate to disk, and
                      returns a 200 response with empty plaintext body"
              (try
                (is (false? (fs/exists? expected-path)))
                (let [response (handle-put-certificate-request! "test-agent" csr-stream settings)]
                  (is (true? (fs/exists? expected-path)))
                  (is (= 200 (:status response)))
                  (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                  (is (nil? (:body response))))
                (finally
                  (fs/delete expected-path)))))))

      (testing "when autosign results in false"
        (doseq [value [false
                       (test-autosign-file "ruby-autosign-executable-false")
                       (test-autosign-file "autosign-whitelist.conf")]]
          (let [settings      (assoc settings :autosign value)
                csr-stream    (io/input-stream (test-pem-file "foo-agent-csr.pem"))
                expected-path (ca/path-to-cert-request (:csrdir settings) "foo-agent")]

            (testing "it writes the CSR to disk and returns a
                     200 response with empty plaintext body"
              (try
                (is (false? (fs/exists? expected-path)))
                (let [response (handle-put-certificate-request! "foo-agent" csr-stream settings)]
                  (is (true? (fs/exists? expected-path)))
                  (is (false? (fs/exists? (ca/path-to-cert (:signeddir settings) "foo-agent"))))
                  (is (= 200 (:status response)))
                  (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                  (is (nil? (:body response))))
                (finally
                  (fs/delete expected-path)))))))

      (testing "when $allow-duplicate-certs is false and we receive a new CSR,
                return a 400 response and error message"
        (let [settings   (assoc settings :allow-duplicate-certs false)
              csr-stream (io/input-stream static-csr)]
          ;; Put the duplicate in place
          (fs/copy static-csr (ca/path-to-cert-request (:csrdir settings) "test-agent"))
          (let [response (handle-put-certificate-request! "test-agent" csr-stream settings)]
            (is (logged? #"ignoring certificate request" :error))
            (is (= 400 (:status response)))
            (is (true? (.contains (:body response) "ignoring certificate request"))))))

      (testing "when the subject CN on a CSR does not match the hostname specified
                in the URL, the response is a 400"
        (let [csr-stream (io/input-stream static-csr)
              response   (handle-put-certificate-request! "NOT-test-agent" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (re-matches
               #"Instance name \"test-agent\" does not match requested key \"NOT-test-agent\""
               (:body response)))))

      (testing "when the public key on the CSR is bogus, the response is a 400"
        (let [csr-with-bad-public-key (test-pem-file "luke.madstop.com-bad-public-key.pem")
              csr-stream              (io/input-stream csr-with-bad-public-key)
              response                (handle-put-certificate-request!
                                       "luke.madstop.com" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "CSR contains a public key that does not correspond to the signing key"
                 (:body response)))))

      (testing "when the CSR has disallowed extensions on it, the response is a 400"
        (let [csr-with-bad-ext (test-pem-file "meow-bad-extension.pem")
              csr-stream       (io/input-stream csr-with-bad-ext)
              response         (handle-put-certificate-request!
                                "meow" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "Found extensions that are not permitted: 1.9.9.9.9.9.9"
                 (:body response))))

        (let [csr-with-bad-ext (test-pem-file "woof-bad-extensions.pem")
              csr-stream       (io/input-stream csr-with-bad-ext)
              response         (handle-put-certificate-request!
                                "woof" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "Found extensions that are not permitted: 1.9.9.9.9.9.0, 1.9.9.9.9.9.1"
                 (:body response)))))

      (testing "when the CSR subject contains invalid characters, the response is a 400"
        ;; These test cases are lifted out of the puppet spec tests.
        (let [bad-csrs #{{:subject "super/bad"
                          :csr     (test-pem-file "bad-subject-name-1.pem")}

                         {:subject "not\neven\tkind\rof"
                          :csr     (test-pem-file "bad-subject-name-2.pem")}

                         {:subject "hidden\b\b\b\b\b\bmessage"
                          :csr     (test-pem-file "bad-subject-name-3.pem")}}]

          (doseq [{:keys [subject csr]} bad-csrs]
            (let [csr-stream (io/input-stream csr)
                  response   (handle-put-certificate-request!
                              subject csr-stream settings)]
              (is (= 400 (:status response)))
              (is (= "Subject contains unprintable or non-ASCII characters"
                     (:body response)))))))

      (testing "no wildcards allowed"
        (let [csr-with-wildcard (test-pem-file "bad-subject-name-wildcard.pem")
              csr-stream        (io/input-stream csr-with-wildcard)
              response          (handle-put-certificate-request!
                                 "foo*bar" csr-stream settings)]
          (is (= 400 (:status response)))
          (is (= "Subject contains a wildcard, which is not allowed: foo*bar"
                 (:body response)))))

      (testing "a CSR w/ DNS alt-names gets a specific error response"
        (let [csr (io/input-stream (test-pem-file "hostwithaltnames.pem"))
              response (handle-put-certificate-request!
                        "hostwithaltnames" csr settings)]
          (is (= 400 (:status response)))
          (is (= (:body response)
                 (str "CSR 'hostwithaltnames' contains subject alternative names "
                      "(DNS:altname1, DNS:altname2, DNS:altname3), which are disallowed. "
                      "Use `puppet cert --allow-dns-alt-names sign hostwithaltnames` to sign this request."))))))))

(deftest certificate-status-test
  (testing "read requests"
    (let [test-app (-> (compojure-app (testutils/ca-settings cadir) "42.42.42")
                       (wrap-with-ssl-client-cert))]
      (testing "GET /certificate_status"
        (doseq [[subject status] [["localhost" localhost-status]
                                  ["test-agent" test-agent-status]
                                  ["revoked-agent" revoked-agent-status]]]
          (testing subject
            (let [response (test-app
                            {:uri (str "/production/certificate_status/" subject)
                             :request-method :get})]
              (is (= 200 (:status response)) (str "Error requesting status for " subject))
              (is (= status (json/parse-string (:body response) true))))))

        (testing "returns a 404 when a non-existent certname is given"
          (let [request {:uri "/production/certificate_status/doesnotexist"
                         :request-method :get}
                response (test-app request)]
            (is (= 404 (:status response)))))

        (testing "honors 'Accept: pson' header"
          (let [request {:uri "/production/certificate_status/localhost"
                         :request-method :get
                         :headers {"accept" "pson"}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "text/pson"))))

        (testing "honors 'Accept: text/pson' header"
          (let [request {:uri "/production/certificate_status/localhost"
                         :request-method :get
                         :headers {"accept" "text/pson"}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "text/pson"))))

        (testing "honors 'Accept: application/json' header"
          (let [request {:uri "/production/certificate_status/localhost"
                         :request-method :get
                         :headers {"accept" "application/json"}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "application/json")))))

      (testing "GET /certificate_statuses"
        (let [response (test-app
                        {:uri "/production/certificate_statuses/thisisirrelevant"
                         :request-method :get})]
          (is (= 200 (:status response)))
          (is (= #{localhost-status test-agent-status revoked-agent-status}
                 (set (json/parse-string (:body response) true)))))

        (testing "with 'Accept: pson'"
          (let [response (test-app
                          {:uri "/production/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {"accept" "pson"}})]
          (is (= 200 (:status response)))
          (is (.startsWith (get-in response [:headers "Content-Type"]) "text/pson"))
          (is (= #{localhost-status test-agent-status revoked-agent-status}
                 (set (json/parse-string (:body response) true))))))

        (testing "with 'Accept: text/pson'"
          (let [response (test-app
                          {:uri "/production/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {"accept" "text/pson"}})]
          (is (= 200 (:status response)))
          (is (.startsWith (get-in response [:headers "Content-Type"]) "text/pson"))
          (is (= #{localhost-status test-agent-status revoked-agent-status}
                 (set (json/parse-string (:body response) true))))))

        (testing "with 'Accept: application/json'"
          (let [response (test-app
                          {:uri "/production/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {"accept" "application/json"}})]
          (is (= 200 (:status response)))
          (is (.startsWith (get-in response [:headers "Content-Type"]) "application/json"))
          (is (= #{localhost-status test-agent-status revoked-agent-status}
                 (set (json/parse-string (:body response) true)))))))))

  (testing "write requests"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (compojure-app settings "42.42.42")
                       (wrap-with-ssl-client-cert))]
      (testing "PUT"
        (testing "signing a cert"
          (let [signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
            (is (false? (fs/exists? signed-cert-path)))
            (let [response (test-app
                            {:uri "/production/certificate_status/test-agent"
                             :request-method :put
                             :body (body-stream "{\"desired_state\":\"signed\"}")})]
              (is (true? (fs/exists? signed-cert-path)))
              (is (= 204 (:status response))
                  (ks/pprint-to-string response)))))

        (testing "revoking a cert"
          (let [cert (utils/pem->cert (ca/path-to-cert (:signeddir settings) "localhost"))]
            (is (false? (utils/revoked? (utils/pem->crl (:cacrl settings)) cert)))
            (let [response (test-app
                            {:uri "/production/certificate_status/localhost"
                             :request-method :put
                             :body (body-stream "{\"desired_state\":\"revoked\"}")})]
              (is (true? (utils/revoked? (utils/pem->crl (:cacrl settings)) cert)))
              (is (= 204 (:status response))))))

        (testing "no body results in a 400"
          (let [request  {:uri "/production/certificate_status/test-agent"
                          :request-method :put}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response) "Empty request body."))))

        (testing "a body that isn't JSON results in a 400"
          (let [request  {:body (body-stream "this is not JSON")
                          :uri "/production/certificate_status/test-agent"
                          :request-method :put}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response) "Request body is not JSON."))))

        (testing "invalid cert status results in a 400"
          (let [request  {:uri "/production/certificate_status/test-agent"
                          :request-method :put
                          :body (body-stream "{\"desired_state\":\"bogus\"}")}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response)
                   "State bogus invalid; Must specify desired state of 'signed' or 'revoked' for host test-agent."))))

        (testing "returns a 404 when a non-existent certname is given"
          (let [request {:uri "/production/certificate_status/doesnotexist"
                         :request-method :put
                         :body (body-stream "{\"desired_state\":\"signed\"}")}
                response (test-app request)]
            (is (= 404 (:status response)))
            (is (= "Invalid certificate subject." (:body response)))))

        (testing "Additional error handling on PUT requests"
          (let [settings (testutils/ca-sandbox! cadir)
                test-app (-> (compojure-app settings "42.42.42")
                             (wrap-with-ssl-client-cert))]

            (testing "Asking to revoke a cert that hasn't been signed yet is a 409"
              (let [request {:uri            "/production/certificate_status/test-agent"
                             :request-method :put
                             :body           (body-stream "{\"desired_state\":\"revoked\"}")}
                    response (test-app request)]

                (is (= 409 (:status response))
                    (ks/pprint-to-string response))
                (is (= (:body response)
                       "Cannot revoke certificate for host test-agent without a signed certificate")
                    (ks/pprint-to-string response))))

            (testing "trying to sign a cert that's already signed is a 409"
              (let [request {:uri            "/production/certificate_status/localhost"
                             :request-method :put
                             :body           (body-stream "{\"desired_state\":\"signed\"}")}
                    response (test-app request)]
                (is (= 409 (:status response)))
                (is (= (:body response)
                       "Cannot sign certificate for host localhost without a certificate request"))))

            (testing "trying to revoke a cert that's already revoked is a 204"
              (let [request {:uri            "/production/certificate_status/revoked-agent"
                             :request-method :put
                             :body           (body-stream "{\"desired_state\":\"revoked\"}")}
                    response (test-app request)]
                (is (= 204 (:status response))))))))

      (testing "DELETE"
        (let [csr (ca/path-to-cert-request (:csrdir settings) "test-agent")]
          (fs/copy (ca/path-to-cert-request csrdir "test-agent") csr)
          (is (true? (fs/exists? csr)))
          (is (= 204 (:status (test-app
                               {:uri "/production/certificate_status/test-agent"
                                :request-method :delete}))))
          (is (false? (fs/exists? csr))))

        (let [cert (ca/path-to-cert (:signeddir settings) "revoked-agent")]
          (is (true? (fs/exists? cert)))
          (is (= 204 (:status (test-app
                               {:uri "/production/certificate_status/revoked-agent"
                                :request-method :delete}))))
          (is (false? (fs/exists? cert))))

        (testing "returns a 404 when a non-existent certname is given"
          (is (= 404 (:status (test-app
                               {:uri "/production/certificate_status/doesnotexist"
                                :request-method :delete}))))))))

  (testing "a signing request w/ a 'application/json' content-type succeeds"
    (let [settings         (testutils/ca-sandbox! cadir)
          test-app         (-> (compojure-app settings "42.42.42")
                               (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri            "/production/certificate_status/test-agent"
                       :request-method :put
                       :headers        {"content-type" "application/json"}
                       :body           (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'text/pson' content-type succeeds"
    (let [settings         (testutils/ca-sandbox! cadir)
          test-app         (-> (compojure-app settings "42.42.42")
                               (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri            "/production/certificate_status/test-agent"
                       :request-method :put
                       :headers        {"content-type" "text/pson"}
                       :body           (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'pson' content-type succeeds"
    (let [settings         (testutils/ca-sandbox! cadir)
          test-app         (-> (compojure-app settings "42.42.42")
                               (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri            "/production/certificate_status/test-agent"
                       :request-method :put
                       :headers        {"content-type" "pson"}
                       :body           (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a bogus content-type header results in a HTTP 415"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (compojure-app settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          response (test-app
                    {:uri            "/production/certificate_status/test-agent"
                     :request-method :put
                     :headers        {"content-type" "bogus"}
                     :body           (body-stream "{\"desired_state\":\"signed\"}")})]
      (is (= 415 (:status response))
          (ks/pprint-to-string response))
      (is (= (:body response) "Unsupported media type.")))))

(deftest cert-status-invalid-csrs
  (testing "Asking /certificate_status to sign invalid CSRs"
    (let [settings  (assoc (testutils/ca-settings cadir)
                      :csrdir (str test-resources-dir "/alternate-csrdir"))
          test-app (-> (compojure-app settings "42.42.42")
                       (wrap-with-ssl-client-cert))]

      (testing "one example - a CSR with DNS alt-names"
        (let [request {:uri            "/production/certificate_status/hostwithaltnames"
                       :request-method :put
                       :body           (body-stream "{\"desired_state\":\"signed\"}")}
              response (test-app request)]
          (is (= 409 (:status response))
              (ks/pprint-to-string response))
          (is (= (:body response)
                 (str "CSR 'hostwithaltnames' contains subject alternative names "
                      "(DNS:altname1, DNS:altname2, DNS:altname3), which are disallowed. "
                      "Use `puppet cert --allow-dns-alt-names sign hostwithaltnames` "
                      "to sign this request.")))))

      (testing "another example - a CSR with an invalid extension"
        (let [request {:uri            "/production/certificate_status/meow"
                       :request-method :put
                       :body           (body-stream "{\"desired_state\":\"signed\"}")}
              response (test-app request)]
          (is (= 409 (:status response))
              (ks/pprint-to-string response))
          (is (= (:body response)
                 "Found extensions that are not permitted: 1.9.9.9.9.9.9")))))))

(deftest cert-status-duplicate-certs
  (testing "signing a certificate doesn't depend on $allow-duplicate-certs"
    (doseq [bool [false true]]
      (testing bool
        (let [settings    (assoc (testutils/ca-sandbox! cadir)
                            :allow-duplicate-certs bool)
              test-app    (-> (compojure-app settings "1.2.3.4")
                              (wrap-with-ssl-client-cert))
              signed-path (ca/path-to-cert (:signeddir settings) "test-agent")]
          (is (false? (fs/exists? signed-path)))
          (let [response (test-app
                          {:uri "/production/certificate_status/test-agent"
                           :request-method :put
                           :body (body-stream "{\"desired_state\":\"signed\"}")})]
            (is (true? (fs/exists? signed-path)))
            (is (= 204 (:status response))
                (ks/pprint-to-string response))))))))

(deftest cert-status-access-control
  (testing "a request with no certificate is rejected with 403 Forbidden"
    (let [test-app (compojure-app (testutils/ca-settings cadir) "1.2.3.4")]
      (doseq [endpoint ["certificate_status" "certificate_statuses"]]
        (testing endpoint
          (let [response (test-app
                          {:uri (str "/production/" endpoint "/test-agent")
                           :request-method :get})]
            (is (= 403 (:status response)))
            (is (= "Forbidden." (:body response))))))))

  (testing "a request with a certificate not on the whitelist is rejected"
    (let [settings (assoc (testutils/ca-settings cadir)
                     :access-control {:certificate-status
                                      {:client-whitelist []}})
          test-app (compojure-app settings "1.2.3.4")]
      (doseq [endpoint ["certificate_status" "certificate_statuses"]]
        (testing endpoint
          (let [response (test-app
                          {:uri (str "/production/" endpoint "/test-agent")
                           :request-method :get
                           :ssl-client-cert localhost-cert})]
            (is (= 403 (:status response)))
            (is (= "Forbidden." (:body response))))))))

  (testing "a request with a certificate that is on the whitelist is allowed"
    (doseq [whitelist [["localhost"] ["foo!" "localhost"]]]
      (testing "certificate_status"
        (let [settings (assoc (testutils/ca-settings cadir)
                         :access-control {:certificate-status
                                          {:client-whitelist whitelist}})
              test-app (compojure-app settings "1.2.3.4")
              response (test-app
                        {:uri             "/production/certificate_status/test-agent"
                         :request-method  :get
                         :ssl-client-cert localhost-cert})]
          (is (= 200 (:status response)))
          (is (= test-agent-status (json/parse-string (:body response) true)))))

      (testing "certificate_statuses"
        (let [settings (assoc (testutils/ca-settings cadir)
                         :access-control {:certificate-status
                                          {:client-whitelist whitelist}})
              test-app (compojure-app settings "1.2.3.4")
              response (test-app
                        {:uri             "/production/certificate_statuses/all"
                         :request-method  :get
                         :ssl-client-cert localhost-cert})]
          (is (= 200 (:status response)))
          (is (= #{test-agent-status revoked-agent-status localhost-status}
                 (set (json/parse-string (:body response) true))))))))

  (testing "access control can be disabled"
    (let [settings (assoc (testutils/ca-settings cadir)
                     :access-control {:certificate-status
                                      {:authorization-required false
                                       :client-whitelist []}})
          test-app (compojure-app settings "1.2.3.4")]

      (testing "certificate_status"
        (let [response (test-app
                        {:uri            "/production/certificate_status/test-agent"
                         :request-method :get})]
          (is (= 200 (:status response)))
          (is (= test-agent-status (json/parse-string (:body response) true)))))

      (testing "certificate_statuses"
        (let [response (test-app
                        {:uri            "/production/certificate_statuses/all"
                         :request-method :get})]
          (is (= 200 (:status response)))
          (is (= #{test-agent-status revoked-agent-status localhost-status}
                 (set (json/parse-string (:body response) true)))))))))
