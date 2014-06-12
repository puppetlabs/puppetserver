(ns puppetlabs.master.services.ca.certificate-authority-core-test
  (:require [puppetlabs.master.services.ca.certificate-authority-core :refer :all]
            [puppetlabs.master.certificate-authority :as ca]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(deftest crl-endpoint-test
  (testing "implementation of the CRL endpoint"
    (let [response (handle-get-certificate-revocation-list
                     {:cacrl "./test-resources/config/master/conf/ssl/crl.pem"})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response))))))

(deftest handle-put-certificate-request!-test
  (let [cadir     "./test-resources/config/master/conf/ssl/ca"
        signeddir (str cadir "/signed")
        csrdir    (str cadir "/requests")
        settings  {:ca-name   "some CA"
                   :cakey     (str cadir "/ca_key.pem")
                   :signeddir signeddir
                   :csrdir    csrdir
                   :ca-ttl    100}
        csr-path  (ca/path-to-cert-request csrdir "test-agent")]

    (testing "when autosign is true"
      (let [settings      (assoc settings :autosign true)
            csr           (io/input-stream csr-path)
            expected-path (ca/path-to-cert signeddir "test-agent")]

        (testing "it signs the CSR, writes the certificate to disk, and
                 returns a 200 response with empty plaintext body"
          (try
            (is (false? (fs/exists? expected-path)))
            (let [response (handle-put-certificate-request! "test-agent" csr settings)]
              (is (true? (fs/exists? expected-path)))
              (is (= 200 (:status response)))
              (is (= "text/plain" (get-in response [:headers "Content-Type"])))
              (is (nil? (:body response))))
            (finally
              (fs/delete expected-path))))))

    (testing "when autosign is false"
      (let [settings      (assoc settings :autosign false)
            csr           (io/input-stream csr-path)
            expected-path (ca/path-to-cert-request csrdir "foo-agent")]

        (testing "it writes the CSR to disk and returns a
                 200 response with empty plaintext body"
          (try
            (is (false? (fs/exists? expected-path)))
            (let [response (handle-put-certificate-request! "foo-agent" csr settings)]
              (is (true? (fs/exists? expected-path)))
              (is (false? (fs/exists? (ca/path-to-cert signeddir "foo-agent"))))
              (is (= 200 (:status response)))
              (is (= "text/plain" (get-in response [:headers "Content-Type"])))
              (is (nil? (:body response))))
            (finally
              (fs/delete expected-path))))))))
