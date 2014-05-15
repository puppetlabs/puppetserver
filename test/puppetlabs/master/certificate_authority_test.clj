(ns puppetlabs.master.certificate-authority-test
  (:import (java.io StringReader)
           (org.joda.time Period DateTime))
  (:require [puppetlabs.master.certificate-authority :refer :all]
            [puppetlabs.certificate-authority.core :as utils]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]))

(def ssl-dir "./test-resources/config/master/conf/ssl")
(def certdir (str ssl-dir "/certs"))
(def cacert (str certdir "/ca.pem"))
(def csrdir (str ssl-dir "/ca/requests"))
(def cakey (str ssl-dir "/ca/ca_key.pem"))
(def cacrl (str ssl-dir "/ca/ca_crl.pem"))

(deftest get-certificate-test
  (testing "returns CA certificate when subject is 'ca'"
    (let [actual    (get-certificate "ca" cacert certdir)
          expected  (slurp cacert)]
      (is (= expected actual))))

  (testing "returns localhost certificate when subject is 'localhost'"
    (let [localhost-cert (get-certificate "localhost" cacert certdir)
          expected       (slurp (path-to-cert certdir "localhost"))]
      (is (= expected localhost-cert))))

  (testing "returns nil when certificate not found for subject"
    (is (nil? (get-certificate "not-there" certdir cacert)))))

(deftest get-certificate-request-test
  (testing "returns certificate request for subject"
    (let [cert-req (get-certificate-request "test-agent" csrdir)
          expected (slurp (path-to-cert-request csrdir "test-agent"))]
      (is (= expected cert-req))))

  (testing "returns nil when certificate request not found for subject"
    (is (nil? (get-certificate-request "not-there" csrdir)))))

(deftest autosign-certificate-request!-test
  (testing "requests are autosigned and saved to disk"
    (let [subject            "test-agent"
          request-stream     (-> csrdir
                                 (path-to-cert-request subject)
                                 io/input-stream)
          expected-cert-path (path-to-cert certdir subject)
          now                (DateTime/now)
          ttl                (-> 6
                                 Period/days
                                 .toStandardSeconds
                                 .getSeconds)]
      (try
        (let [expiration (autosign-certificate-request! subject
                                                        request-stream
                                                        cakey "test ca"
                                                        certdir
                                                        ttl)]
          (let [duration (Period. now expiration)]
            (is (= 6 (.getDays duration))
                "Cert expiration was incorrect")))
        (is (fs/exists? expected-cert-path))
        (let [signed-cert      (-> expected-cert-path
                                   utils/pem->certs
                                   first)
              expected-subject "CN=test-agent"
              expected-issuer  "CN=test ca"]
          (is (= expected-subject (-> signed-cert .getSubjectX500Principal .getName)))
          (is (= expected-issuer (-> signed-cert .getIssuerX500Principal .getName))))
        (finally
          (fs/delete expected-cert-path))))))

(deftest get-certificate-revocation-list-test
  (testing "`get-certificate-revocation-list` returns a path to valid CRL file."
      (let [crl         (get-certificate-revocation-list cacrl)
            issuer-name (-> crl
                            StringReader.
                            utils/pem->objs
                            first
                            .getIssuer
                            utils/x500-name->CN)]
        (is (= "Puppet CA: localhost" issuer-name)))))

;; TODO verify contents of each created file (PE-3238)
(deftest initialize!-test
  (let [tmp-ssl-dir       (fs/temp-dir "")
        master-certname   "master-foo"
        ca-file-paths     {:capub       (str tmp-ssl-dir "/capub")
                           :cakey       (str tmp-ssl-dir "/cakey")
                           :cacert      (str tmp-ssl-dir "/cacert")
                           :localcacert (str tmp-ssl-dir "/localcacert")
                           :cacrl       (str tmp-ssl-dir "/cacrl")}
        master-file-paths {:hostpubkey  (str tmp-ssl-dir "/hostpubkey")
                           :hostprivkey (str tmp-ssl-dir "/hostprivkey")
                           :hostcert    (str tmp-ssl-dir "/hostcert")}
        expected-files    (concat (vals ca-file-paths)
                                  (vals master-file-paths))]

    (testing "Keylength can be configured"
      (try
        (initialize! ca-file-paths master-file-paths master-certname "test ca" 512)
        (is (= 512 (-> ca-file-paths
                       :cakey
                       utils/pem->private-key
                       .getModulus
                       .bitLength)))
        (finally
          (fs/delete-dir tmp-ssl-dir)))

      (testing "but has a default value"
        (try
          (initialize! ca-file-paths master-file-paths master-certname "test ca")
          (is (= utils/default-key-length
                 (-> ca-file-paths
                     :cakey
                     utils/pem->private-key
                     .getModulus
                     .bitLength)))
          (finally
            (fs/delete-dir tmp-ssl-dir)))))

    (testing "Generated SSL file"
      (try
        (initialize! ca-file-paths master-file-paths master-certname "test ca" 512)
        (doseq [file expected-files]
          (testing file
            (is (fs/exists? file))))

        (finally
          (fs/delete-dir tmp-ssl-dir))))

    (testing "Does not create new files if they all exist"
      (try
        (create-parent-directories! expected-files)
        (doseq [file expected-files]
          (spit file "content that should be unused"))
        (initialize! ca-file-paths master-file-paths master-certname "test ca")
        (doseq [file expected-files]
          (is (= "content that should be unused" (slurp file))
              "Existing file was replaced"))
        (finally
          (fs/delete-dir tmp-ssl-dir))))))
