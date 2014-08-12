(ns puppetlabs.master.services.ca.certificate-authority-core-test
  (:require [puppetlabs.master.services.ca.certificate-authority-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.master.certificate-authority :as ca]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [ring.mock.request :as mock]
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
   :load-path             ["ruby/puppet/lib" "ruby/facter/lib"]})

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
                     "dev-resources/config/master/conf/ruby-autosign-executable"
                     "dev-resources/config/master/conf/autosign-whitelist.conf"]]
        (let [settings      (assoc settings :autosign value)
              csr-stream    (io/input-stream csr-path)
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
                 (:body response))))))))
