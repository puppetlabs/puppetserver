(ns puppetlabs.master.certificate-authority-test
  (:import (java.io StringReader)
           (org.joda.time Period DateTime))
  (:require [puppetlabs.master.certificate-authority :refer :all]
            [puppetlabs.certificate-authority.core :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]))

(def cadir "./test-resources/config/master/conf/ssl/ca")
(def cacert (str cadir "/ca_crt.pem"))
(def cakey (str cadir "/ca_key.pem"))
(def cacrl (str cadir "/ca_crl.pem"))
(def csrdir (str cadir "/requests"))
(def signeddir (str cadir "/signed"))

(deftest get-certificate-test
  (testing "returns CA certificate when subject is 'ca'"
    (let [actual   (get-certificate "ca" cacert signeddir)
          expected (slurp cacert)]
      (is (= expected actual))))

  (testing "returns localhost certificate when subject is 'localhost'"
    (let [localhost-cert (get-certificate "localhost" cacert signeddir)
          expected       (slurp (path-to-cert signeddir "localhost"))]
      (is (= expected localhost-cert))))

  (testing "returns nil when certificate not found for subject"
    (is (nil? (get-certificate "not-there" cacert signeddir)))))

(deftest get-certificate-request-test
  (testing "returns certificate request for subject"
    (let [cert-req (get-certificate-request "test-agent" csrdir)
          expected (slurp (path-to-cert-request csrdir "test-agent"))]
      (is (= expected cert-req))))

  (testing "returns nil when certificate request not found for subject"
    (is (nil? (get-certificate-request "not-there" csrdir)))))

(deftest autosign-csr?-test
  (testing "boolean values for 'autosign'"
    (is (true? (autosign-csr? true)))
    (is (true? (autosign-csr? "true")))
    (is (false? (autosign-csr? false)))
    (is (false? (autosign-csr? "false")))
    (is (false? (autosign-csr? "/foo/bar/autosign.conf")))))

(deftest save-certificate-request!-test
  (testing "requests are saved to disk"
    (let [csr-stream (io/input-stream (path-to-cert-request csrdir "test-agent"))
          path       (path-to-cert-request csrdir "foo")]
      (try
        (is (false? (fs/exists? path)))
        (save-certificate-request! "foo" csr-stream csrdir)
        (is (true? (fs/exists? path)))
        (is (= (get-certificate-request csrdir "foo")
               (get-certificate-request csrdir "test-agent")))
        (finally
          (fs/delete path))))))

(deftest autosign-certificate-request!-test
  (let [subject            "test-agent"
        request-stream     (-> csrdir
                               (path-to-cert-request subject)
                               io/input-stream)
        expected-cert-path (path-to-cert signeddir subject)
        now                (DateTime/now)
        ttl                (-> 6
                               Period/days
                               .toStandardSeconds
                               .getSeconds)
        ca-settings        {:ca-name "test ca"
                            :cakey cakey
                            :signeddir signeddir
                            :ca-ttl ttl}]
    (try
      (let [expiration (autosign-certificate-request!
                         subject request-stream ca-settings)]

        (testing "requests are autosigned and saved to disk"
          (is (fs/exists? expected-cert-path))
          (let [signed-cert (utils/pem->cert expected-cert-path)
                subject     (-> signed-cert .getSubjectX500Principal .getName)
                issuer      (-> signed-cert .getIssuerX500Principal .getName)]
            (is (= "CN=test-agent" subject))
            (is (= "CN=test ca" issuer))))

        (testing "cert expiration is correct based on Puppet's ca_ttl setting"
          (let [duration (Period. now expiration)]
            (is (= 6 (.getDays duration))))))

      (finally
        (fs/delete expected-cert-path)))))

(deftest get-certificate-revocation-list-test
  (testing "`get-certificate-revocation-list` returns a valid CRL file."
    (let [crl    (-> cacrl
                     get-certificate-revocation-list
                     StringReader.
                     utils/pem->crl)
          issuer (-> crl .getIssuerX500Principal .getName)]
      (is (= "CN=Puppet CA: localhost" issuer)))))

(let [ssldir          (ks/temp-dir)
      cadir           (str ssldir "/ca")
      ca-settings     {:autosign  true
                       :ca-name   "test ca"
                       :ca-ttl    1
                       :cacrl     (str cadir "/ca_crl.pem")
                       :cacert    (str cadir "/ca_crt.pem")
                       :cakey     (str cadir "/ca_key.pem")
                       :capub     (str cadir "/ca_pub.pem")
                       :csrdir    (str cadir "/requests")
                       :signeddir (str cadir "/signed")}
      cadir-contents  (settings->cadir-paths ca-settings)
      ssldir-contents {:requestdir  (str ssldir "/certificate_requests")
                       :certdir     (str ssldir "/certs")
                       :hostcert    (str ssldir "/certs/master.pem")
                       :localcacert (str ssldir "/certs/ca.pem")
                       :hostprivkey (str ssldir "/private_keys/master.pem")
                       :hostpubkey  (str ssldir "/public_keys/master.pem")}]

  ;; TODO verify contents of each created file (PE-3238)
  (deftest initialize-ca!-test
    (testing "Generated SSL file"
      (try
        (initialize-ca! ca-settings 512)
        (doseq [file (vals cadir-contents)]
          (testing file
            (is (fs/exists? file))))
        (finally
          (fs/delete-dir cadir)))))

  ;; TODO verify contents of each created file (PE-3238)
  (deftest initialize-master!-test
    (testing "Generated SSL file"
      (try
        (initialize-master! ssldir-contents "master" "test ca"
                            (utils/pem->private-key cakey)
                            (utils/pem->cert cacert)
                            512)
        (doseq [file (vals ssldir-contents)]
          (testing file
            (is (fs/exists? file))))
        (finally
          (fs/delete-dir ssldir)))))


  (deftest initialize!-test
    (testing "Generated SSL file"
      (try
        (initialize! ca-settings ssldir-contents "master" 512)
        (doseq [file (concat (vals cadir-contents) (vals ssldir-contents))]
          (testing file
            (is (fs/exists? file))))
        (finally
          (fs/delete-dir ssldir))))

    (testing "Does not create new files if they all exist"
      (let [directories [:csrdir :signeddir :requestdir :certdir]
            all-files   (merge cadir-contents ssldir-contents)
            no-dirs     (vals (apply dissoc all-files directories))]
        (try
          ;; Create the directory structure and dummy files by hand
          (create-parent-directories! (vals all-files))
          (doseq [d directories] (fs/mkdir (d all-files)))
          (doseq [file no-dirs]
            (spit file "unused content"))

          (initialize! ca-settings ssldir-contents "master" 512)

          (doseq [file no-dirs]
            (is (= "unused content" (slurp file))
                "Existing file was replaced"))
          (finally
            (fs/delete-dir ssldir)))))

    (testing "Keylength"
      (doseq [[message f expected]
              [["can be configured"
                (partial initialize! ca-settings ssldir-contents "master" 512)
                512]
               ["has a default value"
                (partial initialize! ca-settings ssldir-contents "master")
                utils/default-key-length]]]
        (testing message
          (try
            (f)
            (is (= expected (-> ca-settings :cakey
                                utils/pem->private-key utils/keylength)))
            (is (= expected (-> ssldir-contents :hostprivkey
                                utils/pem->private-key utils/keylength)))
            (finally
              (fs/delete-dir ssldir))))))))
