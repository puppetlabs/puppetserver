(ns puppetlabs.services.ca.certificate-authority-core-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [me.raynes.fs :as fs]
            [puppetlabs.ssl-utils.core :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.ca.ca-testutils :as testutils]
            [puppetlabs.services.ca.certificate-authority-core :refer [get-wrapped-handler
                                                                       web-routes
                                                                       handle-get-certificate-revocation-list
                                                                       handle-put-certificate-revocation-list!
                                                                       handle-put-certificate-request!
                                                                       handle-delete-certificate-request!]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [logged?] :as logutils]
            [ring.mock.request :as mock]
            [schema.test :as schema-test]
            [puppetlabs.comidi :as comidi]))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def test-resources-dir "./dev-resources/puppetlabs/services/ca/certificate_authority_core_test")
(def cadir (str test-resources-dir "/master/conf/ca"))
(def csrdir (str cadir "/requests"))
(def test-pems-dir (str test-resources-dir "/pems"))
(def autosign-files-dir (str test-resources-dir "/autosign"))

(defn test-pem-file
  [pem-file-name]
  (str test-pems-dir "/" pem-file-name))

(defn test-autosign-file
  [autosign-file-name]
  (ks/absolute-path (str autosign-files-dir "/" autosign-file-name)))

(def localhost-cert
  (utils/pem->cert (test-pem-file "localhost-cert.pem")))

(defn build-ring-handler
  [settings puppet-version]
  (get-wrapped-handler
    (-> (web-routes settings (constantly nil))
        (comidi/routes->handler))
    settings
    ""
    (fn [handler]
      (fn [request]
        (handler request)))
    puppet-version))

(defn gen-csr-input-stream!
  [subject]
  (let [key-pair (utils/generate-key-pair 512)
        csr (utils/generate-certificate-request key-pair (utils/cn subject))
        pem (java.io.ByteArrayOutputStream.)
        _ (utils/obj->pem! csr pem)]
    (io/input-stream (.getBytes (.toString pem)))))

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
  {:authorization_extensions {:pp_cli_auth "true"}
   :dns_alt_names ["DNS:localhost"
                   "DNS:djroomba.vpn.puppetlabs.net"
                   "DNS:puppet.vpn.puppetlabs.net"
                   "DNS:puppet"]
   :fingerprint "88:B1:67:67:78:E2:62:FD:8E:1B:8F:29:CB:2E:C9:41:E1:D3:36:44:27:AB:F9:E4:A7:87:C6:E3:FE:7C:C1:DC"
   :fingerprints {:SHA1 "D4:3F:14:87:B9:80:FF:FF:90:0B:F2:69:81:64:07:B4:D2:65:72:92"
                  :SHA256 "88:B1:67:67:78:E2:62:FD:8E:1B:8F:29:CB:2E:C9:41:E1:D3:36:44:27:AB:F9:E4:A7:87:C6:E3:FE:7C:C1:DC"
                  :SHA512 "E2:97:07:DB:5C:1B:FE:D2:04:4B:94:95:3C:06:4D:2F:6C:B6:67:98:F8:3D:86:82:D1:8E:16:C9:F9:59:8F:19:92:94:69:5B:8B:A8:0A:3E:FF:E4:52:57:79:74:75:79:33:E3:B4:69:C3:16:95:02:4D:D4:F5:60:67:13:11:F9"
                  :default "88:B1:67:67:78:E2:62:FD:8E:1B:8F:29:CB:2E:C9:41:E1:D3:36:44:27:AB:F9:E4:A7:87:C6:E3:FE:7C:C1:DC"}
   :name "localhost"
   :not_after "2025-08-20T22:06:21UTC"
   :not_before "2020-08-20T22:06:21UTC"
   :serial_number 7
   :state "signed"
   :subject_alt_names ["DNS:localhost"
                       "DNS:djroomba.vpn.puppetlabs.net"
                       "DNS:puppet.vpn.puppetlabs.net"
                       "DNS:puppet"]})

(def test-agent-status
  {:authorization_extensions {}
   :dns_alt_names []
   :fingerprint "D4:9C:03:6A:4B:4E:51:87:54:5B:BD:93:8E:4A:06:A7:6F:94:CE:33:62:F9:F4:74:61:4D:20:B1:A9:23:C8:0C"
   :fingerprints {:SHA1 "91:A4:AE:9C:8D:DF:D1:7D:77:85:97:19:C1:85:12:12:89:69:C5:73"
                  :SHA256 "D4:9C:03:6A:4B:4E:51:87:54:5B:BD:93:8E:4A:06:A7:6F:94:CE:33:62:F9:F4:74:61:4D:20:B1:A9:23:C8:0C"
                  :SHA512 "51:DE:0B:B1:B2:1A:5C:3E:2B:D8:84:DE:03:B0:AF:9B:78:2C:A3:9A:70:15:B2:15:00:2C:B5:4F:99:8B:CB:98:9F:CE:C3:28:5C:02:A9:CD:3B:D9:34:E7:CB:D8:E1:5D:24:11:B9:4A:78:FF:68:92:12:CA:B3:A7:D2:89:22:38"
                  :default "D4:9C:03:6A:4B:4E:51:87:54:5B:BD:93:8E:4A:06:A7:6F:94:CE:33:62:F9:F4:74:61:4D:20:B1:A9:23:C8:0C"}
   :name "test-agent"
   :state "requested"
   :subject_alt_names []})

(def revoked-agent-status
  {:authorization_extensions {}
   :dns_alt_names ["DNS:BAR"
                   "DNS:Baz4"
                   "DNS:foo"
                   "DNS:revoked-agent"]
   :subject_alt_names ["DNS:BAR"
                       "DNS:Baz4"
                       "DNS:foo"
                       "DNS:revoked-agent"]
   :fingerprint "26:BE:A0:35:50:5E:C8:9A:BB:FB:12:EC:3A:CD:7E:F4:71:9B:86:C1:3B:CC:3B:3B:44:8D:9D:68:12:6A:C8:E2"
   :fingerprints {:SHA1 "22:37:E2:C9:FB:20:8F:AA:71:72:EE:B7:02:D8:BE:35:D5:91:61:1B"
                  :SHA256 "26:BE:A0:35:50:5E:C8:9A:BB:FB:12:EC:3A:CD:7E:F4:71:9B:86:C1:3B:CC:3B:3B:44:8D:9D:68:12:6A:C8:E2"
                  :SHA512 "7A:35:00:F1:F6:8F:72:01:D7:5F:95:EC:D7:D7:03:DC:BC:55:95:98:F4:48:5E:B4:22:3B:1B:B1:82:B9:6C:59:7C:05:54:86:40:33:CE:3A:9E:75:E9:27:23:95:6E:D8:04:7A:3C:26:8E:0D:70:9B:F6:1F:74:51:29:97:1E:28"
                  :default "26:BE:A0:35:50:5E:C8:9A:BB:FB:12:EC:3A:CD:7E:F4:71:9B:86:C1:3B:CC:3B:3B:44:8D:9D:68:12:6A:C8:E2"}
   :name "revoked-agent"
   :not_after "2025-08-20T20:37:00UTC"
   :not_before "2020-08-20T20:37:00UTC"
   :serial_number 5
   :state "revoked"})

(def test-cert-status
  {:authorization_extensions {}
   :dns_alt_names []
   :subject_alt_names []
   :fingerprint "00:A9:C2:5E:2A:66:18:E2:99:DF:27:13:36:7E:DB:4D:9B:DC:93:DD:3A:B2:69:48:AD:1A:3B:12:45:BF:CF:8A"
   :fingerprints {:SHA1 "CF:F9:E9:7E:05:13:1E:ED:CB:3B:94:49:07:D5:3E:6E:12:8C:5C:E4"
                  :SHA256 "00:A9:C2:5E:2A:66:18:E2:99:DF:27:13:36:7E:DB:4D:9B:DC:93:DD:3A:B2:69:48:AD:1A:3B:12:45:BF:CF:8A"
                  :SHA512 "7F:3A:D0:4C:12:68:FA:1C:B4:DE:7F:B7:39:AE:94:00:33:02:B9:E7:B1:2D:8F:6C:BB:58:84:0A:EF:98:D2:79:9F:12:76:23:58:2F:3D:CE:E9:34:16:88:43:DD:2E:A6:98:5E:33:FB:B1:61:38:5E:58:BC:EA:F9:4A:BC:E8:12"
                  :default "00:A9:C2:5E:2A:66:18:E2:99:DF:27:13:36:7E:DB:4D:9B:DC:93:DD:3A:B2:69:48:AD:1A:3B:12:45:BF:CF:8A"}
   :name "test_cert"
   :not_after "2025-08-20T20:30:32UTC"
   :not_before "2020-08-20T20:30:32UTC"
   :serial_number 3
   :state "signed"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest crl-endpoint-test
  (testing "implementation of the CRL endpoint with no 'If-Modified-Since' header"
    (let [request  (mock/request :get
                                 "/v1/certificate_revocation_list/mynode")
          ca-settings (testutils/ca-settings cadir)
          response (handle-get-certificate-revocation-list
                    request (assoc ca-settings
                              :cacrl (test-pem-file "crl.pem")
                              :infra-crl-path (test-pem-file "crl.pem")
                              :enable-infra-crl false))]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response)))))
  (testing "with a malformed http-date 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate_revocation_list/mynode"
                    :request-method :get
                    :headers        {"If-Modified-Since" "Wed, 21 Oct 2015 07:28:00"}}
          response (ring-app request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))))
  (testing "with older 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate_revocation_list/mynode"
                    :request-method :get
                    :headers        {"If-Modified-Since" "Wed, 21 Oct 2015 07:28:00 GMT"}}
          response (ring-app request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))))
  (testing "with newer 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate_revocation_list/mynode"
                    :request-method :get
                    :headers        {"If-Modified-Since" "Wed, 21 Oct 3015 07:28:00 GMT"}}
          response (ring-app request)]
      (is (= 304 (:status response)))
      (is (nil? (:body response))))))

(deftest certificate-endpoint-test
  (testing "implementation of the CRL endpoint with no 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate/localhost"
                    :request-method :get
                    :headers        {}}
          response (ring-app request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))))
  (testing "with a malformed http-date 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate/localhost"
                    :request-method :get
                    :headers        {"If-Modified-Since" "Wed, 21 Oct 2015 07:28:00"}}
          response (ring-app request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))))
  (testing "with older 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate/localhost"
                    :request-method :get
                    :headers        {"If-Modified-Since" "Wed, 21 Oct 2015 07:28:00 GMT"}}
          response (ring-app request)]
      (is (= 200 (:status response)))
      (is (string? (:body response)))))
  (testing "with newer 'If-Modified-Since' header"
    (let [ring-app (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
          request  {:uri            "/v1/certificate/localhost"
                    :request-method :get
                    :headers        {"If-Modified-Since" "Wed, 21 Oct 3015 07:28:00 GMT"}}
          response (ring-app request)]
      (is (= 304 (:status response)))
      (is (nil? (:body response))))))

(deftest handle-put-certificate-revocation-list!-test
  (let [{:keys [cacrl cacert] :as settings} (testutils/ca-sandbox! cadir)
        fixtures-dir (str test-resources-dir "/update_crls")]
    (fs/copy (str fixtures-dir "/ca_crl.pem") cacrl)
    (fs/copy (str fixtures-dir "/ca_crt.pem") cacert)
    (logutils/with-test-logging
     (testing "with a valid replacement CRL"
       (let [incoming-crl (testutils/pem-to-stream (str fixtures-dir "/new_root_crl.pem"))
             crl-backup-path (str test-resources-dir "/crl_backup.pem")]
         (testutils/with-backed-up-crl
          cacrl crl-backup-path
          (let [response (handle-put-certificate-revocation-list! incoming-crl settings)
                new-crl (utils/pem->crls cacrl)
                old-crl (utils/pem->crls crl-backup-path)]
            (is (= 200 (:status response)))
            (is (> (utils/get-crl-number (last new-crl))
                   (utils/get-crl-number (last old-crl))))))))
     (testing "with an invalid replacement CRL"
       (let [incoming-crl (testutils/pem-to-stream (str fixtures-dir "/multiple_newest_root_crls.pem"))
             crl-backup-path (str test-resources-dir "/crl_backup.pem")]
         (testutils/with-backed-up-crl
          cacrl crl-backup-path
          (let [response (handle-put-certificate-revocation-list! incoming-crl settings)
                new-crl (utils/pem->crls cacrl)
                old-crl (utils/pem->crls crl-backup-path)]
            (is (= 400 (:status response)))
            (is (= "Could not determine newest CRL." (:body response)))
            (is (= new-crl old-crl))))))
     (testing "with infra CRL enabled"
       (let [settings-infra-enabled (assoc settings :enable-infra-crl true)
             infra-crl-path (:infra-crl-path settings-infra-enabled)
             crl-backup-path (str test-resources-dir "/crl_backup.pem")
             infra-crl-backup-path (str test-resources-dir "/infra_crl_backup.pem")]
         (fs/copy (str fixtures-dir "/ca_crl.pem")
                  (:infra-crl-path settings-infra-enabled))
         (testing "with a valid replacement CRL"
           (testutils/with-backed-up-crl
            cacrl crl-backup-path
            (fs/copy infra-crl-path infra-crl-backup-path)
            (try
              (let [incoming-crl (testutils/pem-to-stream (str fixtures-dir "/new_root_crl.pem"))
                    response (handle-put-certificate-revocation-list! incoming-crl
                                                                      settings-infra-enabled)
                    new-crl (utils/pem->crls cacrl)
                    old-crl (utils/pem->crls crl-backup-path)
                    new-infra-crl (utils/pem->crls infra-crl-path)
                    old-infra-crl (utils/pem->crls infra-crl-backup-path)]
                (is (= 200 (:status response)))
                (is (> (utils/get-crl-number (last new-crl))
                       (utils/get-crl-number (last old-crl))))
                (is (> (utils/get-crl-number (last new-infra-crl))
                       (utils/get-crl-number (last old-infra-crl))))
                (is (= (first new-crl) (first old-crl)))
                (is (= (first new-infra-crl) (first old-infra-crl))))
              (finally
                (fs/delete infra-crl-path)
                (fs/move infra-crl-backup-path infra-crl-path)))))
         (testing "with a invalid replacement CRL"
           (testutils/with-backed-up-crl
            cacrl crl-backup-path
            (fs/copy infra-crl-path infra-crl-backup-path)
            (try
              (let [incoming-crls (testutils/pem-to-stream (str fixtures-dir "/multiple_newest_root_crls.pem"))
                    response (handle-put-certificate-revocation-list! incoming-crls
                                                                      settings-infra-enabled)
                    new-crl (utils/pem->crls cacrl)
                    old-crl (utils/pem->crls crl-backup-path)
                    new-infra-crl (utils/pem->crls infra-crl-path)
                    old-infra-crl (utils/pem->crls infra-crl-backup-path)]
                (is (= 400 (:status response)))
                (is (= "Could not determine newest CRL." (:body response)))
                (is (= new-crl old-crl))
                (is (= new-infra-crl old-infra-crl)))
              (finally
                (fs/delete infra-crl-path)
                (fs/move infra-crl-backup-path infra-crl-path))))))))))


(deftest puppet-version-header-test
  (testing "Responses contain a X-Puppet-Version header"
    (let [version-number "42.42.42"
          ring-app (build-ring-handler (testutils/ca-settings cadir) version-number)
          ;; we can just GET the /CRL endpoint, so that's an easy test here.
          request (mock/request :get
                                "/v1/certificate_revocation_list/mynode")
          response (ring-app request)]
      (is (= version-number (get-in response [:headers "X-Puppet-Version"]))))))

(deftest handle-delete-certificate-request!-test
  (let [settings (assoc (testutils/ca-sandbox! cadir)
                        :allow-duplicate-certs true
                        :autosign false)
        request {:authorization {:name "authname"} :remote-addr "1.1.1.1"}]
    (testing "successful csr deletion"
      (logutils/with-test-logging
        (let [subject "happy-agent"
              csr-stream (gen-csr-input-stream! subject)
              expected-path (ca/path-to-cert-request (:csrdir settings) subject)
              request (assoc request :route-params {:subject subject} :body csr-stream)]
          (try
            (handle-put-certificate-request! settings (constantly nil) request)
            (is (true? (fs/exists? expected-path)))
            (let [response (handle-delete-certificate-request! subject settings)
                  msg-matcher (re-pattern (str "Deleted .* for " subject ".*"))]
              (is (false? (fs/exists? expected-path)))
              (is (= 204 (:status response)))
              (is (re-matches msg-matcher (:body response)))
              (is (= "text/plain" (get-in response [:headers "Content-Type"])))
              (is (logged? msg-matcher :debug)))
            (finally
              (fs/delete expected-path))))))
    (testing "Attempted deletion of a non-existant CSR"
      (logutils/with-test-logging
        (let [subject "not-found-agent"
              response (handle-delete-certificate-request! subject settings)
              expected-path (ca/path-to-cert-request (:csrdir settings) subject)
              msg-matcher (re-pattern
                            (str "No cert.*request for " subject " at.*" expected-path))]
          (is (false? (fs/exists? expected-path)))
          (is (= 404 (:status response)))
          (is (re-matches msg-matcher (:body response)))
          (is (= "text/plain" (get-in response [:headers "Content-Type"])))
          (is (logged? msg-matcher :warn)))))
    (testing "Error during deletion of a CSR"
      (logutils/with-test-logging
        (let [subject "err-agent"
              csr-stream (gen-csr-input-stream! subject)
              expected-path (ca/path-to-cert-request (:csrdir settings) subject)
              request (assoc request :route-params {:subject subject} :body csr-stream)]
          (try
            (handle-put-certificate-request! settings (constantly nil) request)
            (is (true? (fs/exists? expected-path)))
            (fs/chmod "-w" (fs/parent expected-path))
            (let [response (handle-delete-certificate-request! subject settings)
                   msg-matcher (re-pattern (str "Path " expected-path " exists but.*"))]
               (is (= 500 (:status response)))
               (is (re-matches msg-matcher (:body response)))
               (is (= "text/plain" (get-in response [:headers "Content-Type"])))
               (is (logged? msg-matcher :error)))
            (finally
              (fs/chmod "+w" (fs/parent expected-path)))))))))

(deftest handle-put-certificate-request!-test
  (let [settings   (assoc (testutils/ca-sandbox! cadir)
                     :allow-duplicate-certs true)
        static-csr (ca/path-to-cert-request csrdir "test-agent")
        request {:authorization {:name "ca certname"} :remote-addr "1.1.1.1" :route-params {:subject "test-agent"}}]
    (logutils/with-test-logging
      (testing "when autosign results in true"
        (doseq [value [true
                       (test-autosign-file "ruby-autosign-executable")
                       (test-autosign-file "autosign-whitelist.conf")]]
          (let [settings      (assoc settings :autosign value)
                csr-stream    (io/input-stream static-csr)
                expected-path (ca/path-to-cert (:signeddir settings) "test-agent")
                request (assoc request :body csr-stream)]

            (testing "it signs the CSR, writes the certificate to disk, and
                      returns a 200 response with empty plaintext body"
              (try
                (is (false? (fs/exists? expected-path)))
                (let [response (handle-put-certificate-request! settings (constantly nil) request)]
                  (is (true? (fs/exists? expected-path)))
                  (is (= 200 (:status response)))
                  (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                  (is (nil? (:body response))))
                (finally
                  (fs/delete expected-path))))))

        (let [ca-cert-file (ca/path-to-cert cadir "ca_crt_multiple_rdns")
              ca-subject-bytes (-> ca-cert-file
                                   (utils/pem->cert)
                                   (.getSubjectX500Principal)
                                   (.getEncoded))
              settings (assoc settings :autosign true :cacert ca-cert-file)
              csr-stream (io/input-stream static-csr)
              expected-path (ca/path-to-cert (:signeddir settings) "test-agent")
              request (assoc request :body csr-stream)]

          (testing "a multi-RDN CA subject is properly set as signed cert's issuer"
            (try
              (is (false? (fs/exists? expected-path)))
              (let [response (handle-put-certificate-request! settings (constantly nil) request)
                    signed-cert-issuer-bytes (-> (utils/pem->cert expected-path)
                                                 (.getIssuerX500Principal)
                                                 (.getEncoded))]
                (is (true? (fs/exists? expected-path)))
                (is (= 200 (:status response)))
                (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                (is (nil? (:body response)))
                (is (= (seq ca-subject-bytes)
                       (seq signed-cert-issuer-bytes))))
              (finally
                (fs/delete expected-path))))))

      (testing "when autosign results in false"
        (doseq [value [false
                       (test-autosign-file "ruby-autosign-executable-false")
                       (test-autosign-file "autosign-whitelist.conf")]]
          (let [settings      (assoc settings :autosign value)
                csr-stream    (io/input-stream (test-pem-file "foo-agent-csr.pem"))
                expected-path (ca/path-to-cert-request (:csrdir settings) "foo-agent")
                request (assoc request :route-params {:subject "foo-agent"} :body csr-stream)]

            (testing "it writes the CSR to disk and returns a
                     200 response with empty plaintext body"
              (try
                (is (false? (fs/exists? expected-path)))
                (let [response (handle-put-certificate-request! settings (constantly nil) request)]
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
              csr-stream (io/input-stream static-csr)
              request    (assoc request :route-params {:subject "test-agent"} :body csr-stream)]
          ;; Put the duplicate in place
          (fs/copy static-csr (ca/path-to-cert-request (:csrdir settings) "test-agent"))
          (let [response (handle-put-certificate-request! settings (constantly nil) request)]
            (is (logged? #"ignoring certificate request" :error))
            (is (= 400 (:status response)))
            (is (true? (.contains (:body response) "ignoring certificate request"))))))

      (testing "when the subject CN on a CSR does not match the hostname specified
                in the URL, the response is a 400"
        (let [csr-stream (io/input-stream static-csr)
              request (assoc request :route-params {:subject "NOT-test-agent"} :body csr-stream)
              response   (handle-put-certificate-request! settings (constantly nil) request)]
          (is (= 400 (:status response)))
          (is (re-matches
               #"Instance name \"test-agent\" does not match requested key \"NOT-test-agent\""
               (:body response)))))

      (testing "when the public key on the CSR is bogus, the response is a 400"
        (let [csr-with-bad-public-key (test-pem-file "luke.madstop.com-bad-public-key.pem")
              csr-stream              (io/input-stream csr-with-bad-public-key)
              request                 (assoc request :route-params {:subject "luke.madstop.com"} :body csr-stream)
              response                (handle-put-certificate-request! settings (constantly nil) request)]
          (is (= 400 (:status response)))
          (is (= "CSR contains a public key that does not correspond to the signing key"
                 (:body response)))))

      (testing "when the CSR has disallowed extensions on it, the response is a 400"
        (let [csr-with-bad-ext (test-pem-file "meow-bad-extension.pem")
              csr-stream       (io/input-stream csr-with-bad-ext)
              request          (assoc request :route-params {:subject "meow"} :body csr-stream)
              response         (handle-put-certificate-request! settings (constantly nil) request)]
          (is (= 400 (:status response)))
          (is (= "Found extensions that are not permitted: 1.9.9.9.9.9.9"
                 (:body response))))

        (let [csr-with-bad-ext (test-pem-file "woof-bad-extensions.pem")
              csr-stream       (io/input-stream csr-with-bad-ext)
              request          (assoc request :route-params {:subject "woof"} :body csr-stream)
              response         (handle-put-certificate-request! settings (constantly nil) request)]
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
                  request    (assoc request :route-params {:subject subject} :body csr-stream)
                  response   (handle-put-certificate-request! settings nil request)]
              (is (= 400 (:status response)))
              (is (= "Subject hostname format is invalid"
                     (:body response)))))))

      (testing "no wildcards allowed"
        (let [csr-with-wildcard (test-pem-file "bad-subject-name-wildcard.pem")
              csr-stream        (io/input-stream csr-with-wildcard)
              request           (assoc request :route-params {:subject "foo*bar"} :body csr-stream)
              response          (handle-put-certificate-request! settings (constantly nil) request)]
          (is (= 400 (:status response)))
          (is (= "Subject contains a wildcard, which is not allowed: foo*bar"
                 (:body response)))))

     (testing "a CSR w/ DNS alt-names and disallowed subject-alt-names gets a specific error response"
       (let [csr (io/input-stream (test-pem-file "hostwithaltnames.pem"))
             settings (assoc settings :allow-subject-alt-names false)
             request (assoc request :route-params {:subject "hostwithaltnames"} :body csr)
             response (handle-put-certificate-request! settings (constantly nil) request)]
         (is (= 400 (:status response)))
         (is (re-find #"hostwithaltnames.*disallowed" (:body response)))))

     (testing "a CSR w/ DNS alt-names and allowed subject-alt-names returns 200"
       (let [csr (io/input-stream (test-pem-file "hostwithaltnames.pem"))
             settings (assoc settings :allow-subject-alt-names true)
             expected-path (ca/path-to-cert-request (:csrdir settings) "hostwithaltnames")
             request (assoc request :route-params {:subject "hostwithaltnames"} :body csr)]
         (try
           (let [response (handle-put-certificate-request! settings (constantly nil) request)]
             (is (= 200 (:status response)))
             (is (= "text/plain" (get-in response [:headers "Content-Type"])))
             (is (nil? (:body response))))
           (finally
             (fs/delete expected-path)))))

     (testing "a CSR w/ DNS and IP alt-names and disallowed subject-alt-names gets a specific error response"
       (let [csr (io/input-stream (test-pem-file "host-with-ip-and-dns-altnames.pem"))
             settings (assoc settings :allow-subject-alt-names false)
             request (assoc request :route-params {:subject "host-with-ip-and-dns-altnames"} :body csr)
             response (handle-put-certificate-request! settings (constantly nil) request)]
         (is (= 400 (:status response)))
         (is (re-find #"host-with-ip-and-dns-altnames" (:body response)))))

     (testing "a CSR w/ DNS and IP alt-names and allowed subject-alt-names returns 200"
       (let [csr (io/input-stream (test-pem-file "host-with-ip-and-dns-altnames.pem"))
             settings (assoc settings :allow-subject-alt-names true)
             expected-path (ca/path-to-cert-request (:csrdir settings) "host-with-ip-and-dns-altnames")
             request (assoc request :route-params {:subject "host-with-ip-and-dns-altnames"} :body csr)]
         (try
           (let [response (handle-put-certificate-request! settings (constantly nil) request)]
             (is (= 200 (:status response)))
             (is (= "text/plain" (get-in response [:headers "Content-Type"])))
             (is (nil? (:body response))))
           (finally
             (fs/delete expected-path)))))

     (testing "a CSR w/ auth extensions and allowed auth extensions returns 200"
       (let [csr (io/input-stream (test-pem-file "csr-auth-extension.pem"))
             settings (assoc settings :allow-authorization-extensions true)
             expected-path (ca/path-to-cert-request (:csrdir settings) "csr-auth-extension")
             request (assoc request :route-params {:subject "csr-auth-extension"} :body csr)]
         (try
           (let [response (handle-put-certificate-request! settings (constantly nil) request)]
             (is (= 200 (:status response)))
             (is (= "text/plain" (get-in response [:headers "Content-Type"])))
             (is (nil? (:body response))))
           (finally
             (fs/delete expected-path)))))

     (testing "a CSR w/ auth extensions and disallowed auth extensions gets a specific error response"
       (let [csr (io/input-stream (test-pem-file "csr-auth-extension.pem"))
             settings (assoc settings :allow-authorization-extensions false)
             request (assoc request :route-params {:subject "csr-auth-extension"} :body csr)
             response (handle-put-certificate-request! settings (constantly nil) request)]
         (is (= 400 (:status response)))
         (is (re-find #"csr-auth-extension.*disallowed" (:body response))))
         
      (testing "when authname provided on signing"
        (logutils/with-test-logging
          (let [csr-stream    (io/input-stream static-csr)
                signee "authname"
                certname "test-agent"
                request {:authorization {:name signee} :remote-addr "1.1.1.1" :body csr-stream :route-params {:subject certname}}
                msg-matcher (re-pattern (str "Entity " signee " signed 1 certificate: " certname))
                expected-path (ca/path-to-cert (:signeddir settings) certname)]
            (try
              (let [response (handle-put-certificate-request! settings (constantly nil) request)]
                (is (= 200 (:status response)))
                (is (logged? msg-matcher :info)))
              (finally
                (fs/delete expected-path))))))

      (testing "when rbac-subject provided on signing"
        (logutils/with-test-logging
          (let [csr-stream    (io/input-stream static-csr)
                signee "rbac-subject"
                certname "test-agent"
                request {:rbac-subject {:login signee} :remote-addr "1.1.1.1" :body csr-stream :route-params {:subject certname}}
                msg-matcher (re-pattern (str "Entity " signee " signed 1 certificate: " certname))
                expected-path (ca/path-to-cert (:signeddir settings) certname)]
            (try
              (let [response (handle-put-certificate-request! settings (constantly nil) request)]
                (is (= 200 (:status response))) 
                (is (logged? msg-matcher :info)))
              (finally
                (fs/delete expected-path))))))

      (testing "when no signee info provided"
        (logutils/with-test-logging
          (let [csr-stream    (io/input-stream static-csr)
                 certname "test-agent"
                 request {:remote-addr "1.1.1.1" :body csr-stream :route-params {:subject certname}}
                 msg-matcher (re-pattern (str "Entity CA signed 1 certificate: " certname))
                 expected-path (ca/path-to-cert (:signeddir settings) certname)]
            (try
              (let [response (handle-put-certificate-request! settings (constantly nil) request)]
                (is (= 200 (:status response)))
                (is (logged? msg-matcher :info)))
              (finally
                (fs/delete expected-path))))))))))

(deftest certificate-status-test
  (testing "read requests"
    (let [test-app (-> (build-ring-handler (testutils/ca-settings cadir) "42.42.42")
                       (wrap-with-ssl-client-cert))]
      (testing "GET /certificate_status"
        (doseq [[subject status] [["localhost" localhost-status]
                                  ["test-agent" test-agent-status]
                                  ["revoked-agent" revoked-agent-status]]]
          (testing subject
            (let [response (test-app
                            {:uri (str "/v1/certificate_status/" subject)
                             :request-method :get})]
              (is (= 200 (:status response)) (str "Error requesting status for " subject))
              (is (= status (json/parse-string (:body response) true))))))

        (testing "returns a 404 when a non-existent certname is given"
          (let [request {:uri "/v1/certificate_status/doesnotexist"
                         :request-method :get}
                response (test-app request)]
            (is (= 404 (:status response)))))

        (testing "returns json when no accept header specified"
          (let [request {:uri "/v1/certificate_status/localhost"
                         :request-method :get
                         :headers {}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "application/json"))))

        (testing "honors 'Accept: pson' header"
          (let [request {:uri "/v1/certificate_status/localhost"
                         :request-method :get
                         :headers {"accept" "pson"}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "pson"))))

        (testing "honors 'Accept: text/pson' header"
          (let [request {:uri "/v1/certificate_status/localhost"
                         :request-method :get
                         :headers {"accept" "text/pson"}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "text/pson"))))

        (testing "honors 'Accept: application/json' header"
          (let [request {:uri "/v1/certificate_status/localhost"
                         :request-method :get
                         :headers {"accept" "application/json"}}
                response (test-app request)]
            (is (= 200 (:status response))
                (ks/pprint-to-string response))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "application/json")))))

      (testing "GET /certificate_statuses"
        (let [response (test-app
                        {:uri "/v1/certificate_statuses/thisisirrelevant"
                         :request-method :get})]
          (is (= 200 (:status response)))
          (is (= #{localhost-status test-agent-status
                   test-cert-status revoked-agent-status}
                 (set (json/parse-string (:body response) true)))))

        (testing "requires ignored path segment"
          (let [response (test-app
                          {:uri "/v1/certificate_statuses/"
                           :request-method :get})]
            (is (= 400 (:status response)))
            (is (= "text/plain; charset=utf-8" (get-in response [:headers "Content-Type"])))
            (is (= "Missing URL Segment" (:body response)))))

        (testing "allows special characters in ignored path segment"
          (let [response (test-app
                          {:uri "/v1/certificate_statuses/*"
                           :request-method :get})]
            (is (= 200 (:status response)))
            (is (= #{localhost-status test-agent-status
                     test-cert-status revoked-agent-status}
                 (set (json/parse-string (:body response) true))))))

        (testing "respects 'state' query param"
          (testing "requested"
            (let [response (test-app
                             {:uri            "/v1/certificate_statuses/thisisirrelevant"
                              :params         {"state" "requested"}
                              :request-method :get})]
              (is (= 200 (:status response)))
              (is (= #{test-agent-status}
                     (set (json/parse-string (:body response) true))))))
          (testing "signed"
            (let [response (test-app
                             {:uri            "/v1/certificate_statuses/thisisirrelevant"
                              :params         {"state" "signed"}
                              :request-method :get})]
              (is (= 200 (:status response)))
              (is (= #{localhost-status test-cert-status}
                     (set (json/parse-string (:body response) true))))))
          (testing "revoked"
            (let [response (test-app
                             {:uri            "/v1/certificate_statuses/thisisirrelevant"
                              :params         {"state" "revoked"}
                              :request-method :get})]
              (is (= 200 (:status response)))
              (is (= #{revoked-agent-status}
                     (set (json/parse-string (:body response) true)))))))

        (testing "returns json when no accept header specified"
          (let [response (test-app
                          {:uri "/v1/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {}})]
            (is (= 200 (:status response)))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "application/json"))
            (is (= #{localhost-status test-agent-status
                     test-cert-status revoked-agent-status}
                   (set (json/parse-string (:body response) true))))))

        (testing "with 'Accept: pson'"
          (let [response (test-app
                          {:uri "/v1/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {"accept" "pson"}})]
            (is (= 200 (:status response)))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "pson"))
            (is (= #{localhost-status test-agent-status
                     test-cert-status revoked-agent-status}
                   (set (json/parse-string (:body response) true))))))

        (testing "with 'Accept: text/pson'"
          (let [response (test-app
                          {:uri "/v1/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {"accept" "text/pson"}})]
            (is (= 200 (:status response)))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "text/pson"))
            (is (= #{localhost-status test-agent-status
                     test-cert-status revoked-agent-status}
                   (set (json/parse-string (:body response) true))))))

        (testing "with 'Accept: application/json'"
          (let [response (test-app
                          {:uri "/v1/certificate_statuses/thisisirrelevant"
                           :request-method :get
                           :headers {"accept" "application/json"}})]
            (is (= 200 (:status response)))
            (is (.startsWith (get-in response [:headers "Content-Type"]) "application/json"))
            (is (= #{localhost-status test-agent-status
                     test-cert-status revoked-agent-status}
                   (set (json/parse-string (:body response) true)))))))))

  (testing "write requests"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))]
      (testing "PUT"
        (testing "signing a cert with an hour ttl"
          (let [csr-path (ca/path-to-cert-request (:csrdir settings) "test-agent")
                signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")
                static-csr (ca/path-to-cert-request csrdir "test-agent")]
            (is (false? (fs/exists? signed-cert-path)))
            (try
              (let [response (test-app
                              {:uri "/v1/certificate_status/test-agent"
                               :request-method :put
                               :body (body-stream "{\"desired_state\":\"signed\",\"cert_ttl\":3600}")})]
                (is (true? (fs/exists? signed-cert-path)))
                (let [cert (utils/pem->cert signed-cert-path)
                      date (java.util.Calendar/getInstance)]
                  (is (nil? (.checkValidity cert (.getTime date))))
                  (.add date (java.util.Calendar/YEAR) 2)
                  (is (thrown?
                       java.security.cert.CertificateExpiredException
                       (.checkValidity cert (.getTime date)))))
                (is (= 204 (:status response))
                    (ks/pprint-to-string response)))
              (finally
                (fs/copy static-csr csr-path)
                (fs/delete signed-cert-path)))))

        (testing "signing a cert with a CA that has multiple RDNs"
          (let [ca-cert-with-mult-rdns (ca/path-to-cert cadir
                                                        "ca_crt_multiple_rdns")
                ca-subject-bytes (-> ca-cert-with-mult-rdns
                                     (utils/pem->cert)
                                     (.getSubjectX500Principal)
                                     (.getEncoded))
                ca-cert-path (:cacert settings)
                csr-path (ca/path-to-cert-request (:csrdir settings) "test-agent")
                signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")
                original-ca-cert (ca/path-to-cert cadir "ca_crt")
                static-csr (ca/path-to-cert-request csrdir "test-agent")]
            (try
              (is (false? (fs/exists? signed-cert-path)))
              (fs/copy ca-cert-with-mult-rdns ca-cert-path)
              (let [response (test-app
                              {:uri "/v1/certificate_status/test-agent"
                               :request-method :put
                               :body (body-stream "{\"desired_state\":\"signed\"}")})
                    signed-cert-issuer-bytes (-> (utils/pem->cert signed-cert-path)
                                                 (.getIssuerX500Principal)
                                                 (.getEncoded))]
                (is (true? (fs/exists? signed-cert-path)))
                (is (= 204 (:status response))
                    (ks/pprint-to-string response))
                (is (= "text/plain" (get-in response [:headers "Content-Type"])))
                (is (= "" (:body response)))
                (is (= (seq ca-subject-bytes)
                       (seq signed-cert-issuer-bytes))))
              (finally
                (fs/copy static-csr csr-path)
                (fs/copy original-ca-cert ca-cert-path)
                (fs/delete signed-cert-path)))))

        (testing "revoking a cert"
          (let [cert (utils/pem->cert (ca/path-to-cert (:signeddir settings) "localhost"))]
            (is (false? (utils/revoked? (utils/pem->crl (:cacrl settings)) cert)))
            (let [response (test-app
                            {:uri "/v1/certificate_status/localhost"
                             :request-method :put
                             :body (body-stream "{\"desired_state\":\"revoked\"}")})]
              (is (true? (utils/revoked? (utils/pem->crl (:cacrl settings)) cert)))
              (is (= 204 (:status response))))))

        (testing "no body results in a 400"
          (let [request  {:uri "/v1/certificate_status/test-agent"
                          :request-method :put}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response) "Empty request body."))))

        (testing "a body that isn't JSON results in a 400"
          (let [request  {:body (body-stream "this is not JSON")
                          :uri "/v1/certificate_status/test-agent"
                          :request-method :put}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response) "Request body is not JSON."))))

        (testing "invalid cert_ttl value results in a 400"
          (let [request  {:uri "/v1/certificate_status/test-agent"
                          :request-method :put
                          :body (body-stream "{\"desired_state\":\"signed\",\"cert_ttl\":\"astring\"}")}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response)
                   "cert_ttl specified for host test-agent must be an integer, not \"astring\""))))

        (testing "invalid cert status results in a 400"
          (let [request  {:uri "/v1/certificate_status/test-agent"
                          :request-method :put
                          :body (body-stream "{\"desired_state\":\"bogus\"}")}
                response (test-app request)]
            (is (= 400 (:status response)))
            (is (= (:body response)
                   "State bogus invalid; Must specify desired state of 'signed' or 'revoked' for host test-agent."))))

        (testing "returns a 404 when a non-existent certname is given"
          (let [request {:uri "/v1/certificate_status/doesnotexist"
                         :request-method :put
                         :body (body-stream "{\"desired_state\":\"signed\"}")}
                response (test-app request)]
            (is (= 404 (:status response)))
            (is (= "text/plain; charset=UTF-8"
                  (get-in response [:headers "Content-Type"]))
              "Unexpected content type for response")
            (is (= "Invalid certificate subject." (:body response)))))

        (testing "Additional error handling on PUT requests"
          (let [settings (testutils/ca-sandbox! cadir)
                test-app (-> (build-ring-handler settings "42.42.42")
                             (wrap-with-ssl-client-cert))]

            (testing "Asking to revoke a cert that hasn't been signed yet is a 409"
              (let [request {:uri            "/v1/certificate_status/test-agent"
                             :request-method :put
                             :body           (body-stream "{\"desired_state\":\"revoked\"}")}
                    response (test-app request)]
                (is (= 409 (:status response))
                    (ks/pprint-to-string response))
                (is (= "text/plain; charset=UTF-8"
                      (get-in response [:headers "Content-Type"]))
                  "Unexpected content type for response")
                (is (= (:body response)
                       "Cannot revoke certificate for host test-agent without a signed certificate")
                    (ks/pprint-to-string response))))

            (testing "trying to sign a cert that's already signed is a 409"
              (let [request {:uri            "/v1/certificate_status/localhost"
                             :request-method :put
                             :body           (body-stream "{\"desired_state\":\"signed\"}")}
                    response (test-app request)]
                (is (= 409 (:status response)))
                (is (= "text/plain; charset=UTF-8"
                      (get-in response [:headers "Content-Type"]))
                  "Unexpected content type for response")
                (is (= (:body response)
                       "Cannot sign certificate for host localhost without a certificate request"))))

            (testing "trying to revoke a cert that's already revoked is a 204"
              (let [request {:uri            "/v1/certificate_status/revoked-agent"
                             :request-method :put
                             :body           (body-stream "{\"desired_state\":\"revoked\"}")}
                    response (test-app request)]
                (is (= 204 (:status response)))))

            (testing "failing to provide a desired_state returns 400"
              (let [request {:uri            "/v1/certificate_status/revoked-agent"
                             :request-method :put
                             :body           (body-stream "{\"foo_state\":\"revoked\"}")}
                    response (test-app request)]
                (is (= 400 (:status response)))
                (is (= (:body response) "Missing required parameter \"desired_state\"")))))))

      (testing "DELETE"
        (let [csr (ca/path-to-cert-request (:csrdir settings) "test-agent")]
          (fs/copy (ca/path-to-cert-request csrdir "test-agent") csr)
          (is (true? (fs/exists? csr)))
          (is (= 204 (:status (test-app
                               {:uri "/v1/certificate_status/test-agent"
                                :request-method :delete}))))
          (is (false? (fs/exists? csr))))

        (let [cert (ca/path-to-cert (:signeddir settings) "revoked-agent")]
          (is (true? (fs/exists? cert)))
          (is (= 204 (:status (test-app
                               {:uri "/v1/certificate_status/revoked-agent"
                                :request-method :delete}))))
          (is (false? (fs/exists? cert))))

        (testing "returns a 404 when a non-existent certname is given"
          (is (= 404 (:status (test-app
                               {:uri "/v1/certificate_status/doesnotexist"
                                :request-method :delete}))))))))

  (testing "a signing request w/ a 'application/json' content-type succeeds"
    (let [settings         (testutils/ca-sandbox! cadir)
          test-app         (-> (build-ring-handler settings "42.42.42")
                               (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri            "/v1/certificate_status/test-agent"
                       :request-method :put
                       :headers        {"content-type" "application/json"}
                       :body           (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'application/json' content-type and charset succeeds"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                     (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri "/v1/certificate_status/test-agent"
                       :request-method :put
                       :headers {"content-type"
                                 "application/json; charset=UTF-8"}
                       :body (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'text/pson' content-type succeeds"
    (let [settings         (testutils/ca-sandbox! cadir)
          test-app         (-> (build-ring-handler settings "42.42.42")
                               (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri            "/v1/certificate_status/test-agent"
                       :request-method :put
                       :headers        {"content-type" "text/pson"}
                       :body           (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'text/pson' content-type and charset succeeds"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                     (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri "/v1/certificate_status/test-agent"
                       :request-method :put
                       :headers {"content-type" "text/pson; charset=UTF-8"}
                       :body (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'pson' content-type succeeds"
    (let [settings         (testutils/ca-sandbox! cadir)
          test-app         (-> (build-ring-handler settings "42.42.42")
                               (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                      {:uri            "/v1/certificate_status/test-agent"
                       :request-method :put
                       :headers        {"content-type" "pson"}
                       :body           (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a 'pson' content-type and charset succeeds"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                     (wrap-with-ssl-client-cert))
          signed-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (is (false? (fs/exists? signed-cert-path)))
      (let [response (test-app
                       {:uri "/v1/certificate_status/test-agent"
                        :request-method :put
                        :headers {"content-type" "pson; charset=UTF-8"}
                        :body (body-stream "{\"desired_state\":\"signed\"}")})]
        (is (true? (fs/exists? signed-cert-path)))
        (is (= 204 (:status response))))))

  (testing "a signing request w/ a bogus content-type header results in a HTTP 415"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          response (test-app
                    {:uri            "/v1/certificate_status/test-agent"
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
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))]

      (testing "one example - a CSR with DNS alt-names"
        (let [request {:uri            "/v1/certificate_status/hostwithaltnames"
                       :request-method :put
                       :body           (body-stream "{\"desired_state\":\"signed\"}")}
              response (test-app request)]
          (is (= 409 (:status response))
              (ks/pprint-to-string response))
          (is (= "text/plain; charset=UTF-8"
                (get-in response [:headers "Content-Type"]))
            "Unexpected content type for response")
          (is (re-find #"hostwithaltnames.*disallowed" (:body response)))))

      (testing "another example - a CSR with an invalid extension"
        (let [request {:uri            "/v1/certificate_status/meow"
                       :request-method :put
                       :body           (body-stream "{\"desired_state\":\"signed\"}")}
              response (test-app request)]
          (is (= 409 (:status response))
              (ks/pprint-to-string response))
          (is (= "text/plain; charset=UTF-8"
                (get-in response [:headers "Content-Type"]))
            "Unexpected content type for response")
          (is (= (:body response)
                 "Found extensions that are not permitted: 1.9.9.9.9.9.9")))))))

(deftest cert-status-duplicate-certs
  (testing "signing a certificate doesn't depend on $allow-duplicate-certs"
    (doseq [bool [false true]]
      (testing bool
        (let [settings    (assoc (testutils/ca-sandbox! cadir)
                            :allow-duplicate-certs bool)
              test-app    (-> (build-ring-handler settings "1.2.3.4")
                              (wrap-with-ssl-client-cert))
              signed-path (ca/path-to-cert (:signeddir settings) "test-agent")]
          (is (false? (fs/exists? signed-path)))
          (let [response (test-app
                          {:uri "/v1/certificate_status/test-agent"
                           :request-method :put
                           :body (body-stream "{\"desired_state\":\"signed\"}")})]
            (is (true? (fs/exists? signed-path)))
            (is (= 204 (:status response))
                (ks/pprint-to-string response))))))))

(deftest cert-status-access-control
  (testing "a request with no certificate is rejected with 403 Forbidden"
    (let [test-app (build-ring-handler (testutils/ca-settings cadir) "1.2.3.4")]
      (doseq [endpoint ["certificate_status" "certificate_statuses"]]
        (testing endpoint
          (let [response (test-app
                          {:uri (str "/v1/" endpoint "/test-agent")
                           :request-method :get})]
            (is (= 403 (:status response)))
            (is (= "Forbidden." (:body response))))))))

  (testing "a request with a certificate not on the whitelist is rejected"
    (let [settings (assoc (testutils/ca-settings cadir)
                     :access-control {:certificate-status
                                      {:client-whitelist ["not-localhost"]}})
          test-app (build-ring-handler settings "1.2.3.4")]
      (doseq [endpoint ["certificate_status" "certificate_statuses"]]
        (testing endpoint
          (let [response (test-app
                          {:uri (str "/v1/" endpoint "/test-agent")
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
              test-app (build-ring-handler settings "1.2.3.4")
              response (test-app
                        {:uri             "/v1/certificate_status/test-agent"
                         :request-method  :get
                         :ssl-client-cert localhost-cert})]
          (is (= 200 (:status response)))
          (is (= test-agent-status (json/parse-string (:body response) true)))))

      (testing "certificate_statuses"
        (let [settings (assoc (testutils/ca-settings cadir)
                         :access-control {:certificate-status
                                          {:client-whitelist whitelist}})
              test-app (build-ring-handler settings "1.2.3.4")
              response (test-app
                        {:uri             "/v1/certificate_statuses/all"
                         :request-method  :get
                         :ssl-client-cert localhost-cert})]
          (is (= 200 (:status response)))
          (is (= #{test-agent-status revoked-agent-status
                   test-cert-status localhost-status}
                 (set (json/parse-string (:body response) true))))))))

  (testing "access control can be disabled"
    (let [settings (assoc (testutils/ca-settings cadir)
                     :access-control {:certificate-status
                                      {:authorization-required false
                                       :client-whitelist []}})
          test-app (build-ring-handler settings "1.2.3.4")]

      (testing "certificate_status"
        (let [response (test-app
                        {:uri            "/v1/certificate_status/test-agent"
                         :request-method :get})]
          (is (= 200 (:status response)))
          (is (= test-agent-status (json/parse-string (:body response) true)))))

      (testing "certificate_statuses"
        (let [response (test-app
                        {:uri            "/v1/certificate_statuses/all"
                         :request-method :get})]
          (is (= 200 (:status response)))
          (is (= #{test-agent-status revoked-agent-status
                   test-cert-status localhost-status}
                 (set (json/parse-string (:body response) true)))))))))

(deftest cert-clean
  (testing "Cleaning one existing cert"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          cert-path (ca/path-to-cert (:signeddir settings) "localhost")
          cert (utils/pem->cert cert-path)
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"certnames\":[\"localhost\"],\"async\":false}")})]
      (is (= 200 (:status response)))
      (is (true? (utils/revoked?
                  (utils/pem->crl (:cacrl settings))
                  cert)))
      (is (false? (fs/exists? cert-path)))))

  (testing "Cleaning multiple existing certs"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          cert-path1 (ca/path-to-cert (:signeddir settings) "localhost")
          cert1 (utils/pem->cert cert-path1)
          cert-path2 (ca/path-to-cert (:signeddir settings) "test_cert")
          cert2 (utils/pem->cert cert-path2)
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"certnames\":[\"localhost\",\"test_cert\"],\"async\":false}")})]
      (is (= 200 (:status response)))
      (is (true? (utils/revoked?
                  (utils/pem->crl (:cacrl settings))
                  cert1)))
      (is (true? (utils/revoked?
                  (utils/pem->crl (:cacrl settings))
                  cert2)))
      (is (false? (fs/exists? cert-path1)))
      (is (false? (fs/exists? cert-path2)))))

  (testing "Cleaning a missing cert"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          cert-path (ca/path-to-cert (:signeddir settings) "localhost")
          cert (utils/pem->cert cert-path)
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"certnames\":[\"missing\",\"localhost\"],\"async\":false}")})]
      (is (= 200 (:status response)))
      (is (= "The following certs do not exist and cannot be revoked: [\"missing\"]"
             (:body response)))
      (is (true? (utils/revoked?
                  (utils/pem->crl (:cacrl settings))
                  cert)))
      (is (false? (fs/exists? cert-path)))))

  (testing "Cleaning a revoked cert succeeds"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          cert-path (ca/path-to-cert (:signeddir settings) "revoked-agent")
          cert (utils/pem->cert cert-path)
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"certnames\":[\"revoked-agent\"],\"async\":false}")})]
      (is (= 200 (:status response)))
      (is (= "Successfully cleaned all certs."
             (:body response)))
      (is (true? (utils/revoked?
                  (utils/pem->crl (:cacrl settings))
                  cert)))
      (is (false? (fs/exists? cert-path)))))

  (testing "Malformed request body"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"certnames\":[\"bad-request\":true}")})]
      (is (= 400 (:status response)))
      (is (= "Request body is not JSON." (:body response)))))

  (testing "Missing certnames"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"async\":false}")})]
      (is (= 400 (:status response)))
      (is (re-matches #"Missing.*certnames.*" (:body response)))))

  (testing "Requesting async mode fails"
    (let [settings (testutils/ca-sandbox! cadir)
          test-app (-> (build-ring-handler settings "42.42.42")
                       (wrap-with-ssl-client-cert))
          response (test-app
                    {:uri "/v1/clean"
                     :request-method :put
                     :body (body-stream
                            "{\"certnames\":[\"revoked-agent\"],\"async\":true}")})]
      (is (= 400 (:status response)))
      (is (= "Async mode is not currently supported."
             (:body response))))))

