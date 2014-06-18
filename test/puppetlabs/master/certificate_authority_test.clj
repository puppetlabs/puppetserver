(ns puppetlabs.master.certificate-authority-test
  (:import (java.io StringReader))
  (:require [puppetlabs.master.certificate-authority :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.certificate-authority.core :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def cadir "./test-resources/config/master/conf/ssl/ca")
(def cacert (str cadir "/ca_crt.pem"))
(def cakey (str cadir "/ca_key.pem"))
(def cacrl (str cadir "/ca_crl.pem"))
(def csrdir (str cadir "/requests"))
(def signeddir (str cadir "/signed"))

(defn assert-subject [o subject]
  (is (= subject (-> o .getSubjectX500Principal .getName))))

(defn assert-issuer [o issuer]
  (is (= issuer (-> o .getIssuerX500Principal .getName))))

(defn tmp-whitelist [& lines]
  (let [whitelist (ks/temp-file)]
    (doseq [line lines]
      (spit whitelist (str line "\n") :append true))
    (str whitelist)))

(defn assert-autosign [whitelist subject]
  (testing subject
    (is (true? (autosign-csr? whitelist subject)))))

(defn assert-no-autosign [whitelist subject]
  (testing subject
    (is (false? (autosign-csr? whitelist subject)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

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
  (testing "boolean values"
    (is (true? (autosign-csr? true "unused")))
    (is (false? (autosign-csr? false "unused"))))

  (testing "whitelist"
    (testing "autosign is false when whitelist doesn't exist"
      (is (false? (autosign-csr? "Foo/conf/autosign.conf" "doubleagent"))))

    (testing "exact certnames"
      (doto (tmp-whitelist "foo"
                           "UPPERCASE"
                           "this.THAT."
                           "bar1234"
                           "AB=foo,BC=bar,CD=rab,DE=oof,EF=1a2b3d")
        (assert-autosign "foo")
        (assert-autosign "UPPERCASE")
        (assert-autosign "this.THAT.")
        (assert-autosign "bar1234")
        (assert-autosign "AB=foo,BC=bar,CD=rab,DE=oof,EF=1a2b3d")
        (assert-no-autosign "Foo")
        (assert-no-autosign "uppercase")
        (assert-no-autosign "this-THAT-")))

    (testing "domain-name globs"
      (doto (tmp-whitelist "*.red"
                           "*.black.local"
                           "*.UPPER.case")
        (assert-autosign "red")
        (assert-autosign ".red")
        (assert-autosign "green.red")
        (assert-autosign "blue.1.red")
        (assert-no-autosign "red.white")
        (assert-autosign "black.local")
        (assert-autosign ".black.local")
        (assert-autosign "blue.black.local")
        (assert-autosign "2.three.black.local")
        (assert-no-autosign "red.local")
        (assert-no-autosign "black.local.white")
        (assert-autosign "one.0.upper.case")
        (assert-autosign "two.upPEr.case")
        (assert-autosign "one-two-three.red")))

    (testing "allow all with '*'"
      (doto (tmp-whitelist "*")
        (assert-autosign "foo")
        (assert-autosign "BAR")
        (assert-autosign "baz-buz.")
        (assert-autosign "0.qux.1.xuq")
        (assert-autosign "AB=foo,BC=bar,CD=rab,DE=oof,EF=1a2b3d")))

    (testing "ignores comments and blank lines"
      (doto (tmp-whitelist "#foo"
                           "  "
                           "bar"
                           ""
                           "# *.baz"
                           "*.qux")
        (assert-no-autosign "foo")
        (assert-no-autosign "  ")
        (assert-autosign "bar")
        (assert-no-autosign "foo.baz")
        (assert-autosign "bar.qux")))

    (testing "invalid lines logged and ignored"
      (doseq [invalid-line ["bar#bar"
                            " #bar"
                            "bar "
                            " bar"]]
        (let [whitelist (tmp-whitelist "foo"
                                       invalid-line
                                       "qux")]
          (assert-autosign whitelist "foo")
          (logutils/with-log-output logs
            (assert-no-autosign whitelist invalid-line)
            (is (logutils/logs-matching
                  (re-pattern (format "Invalid pattern '%s' found in %s"
                                      invalid-line whitelist))
                  @logs))
            (assert-autosign whitelist "qux")))))

    (testing "sample file that covers everything"
      (logutils/with-test-logging
        (doto "test-resources/config/master/conf/autosign-whitelist.conf"
          (assert-no-autosign "aaa")
          (assert-autosign "bbb123")
          (assert-autosign "one_2.red")
          (assert-autosign "1.blacK.6")
          (assert-no-autosign "black.white")
          (assert-no-autosign "coffee")
          (assert-no-autosign "coffee#tea")
          (assert-autosign "qux"))))))

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
        ca-settings        {:ca-name "test ca"
                            :cakey cakey
                            :signeddir signeddir}]
    (try
      (autosign-certificate-request! subject request-stream ca-settings)

      (testing "requests are autosigned and saved to disk"
        (is (fs/exists? expected-cert-path))
        (doto (utils/pem->cert expected-cert-path)
          (assert-subject "CN=test-agent")
          (assert-issuer "CN=test ca")))

      ;; TODO PE-3173 verify signed certificate expiration is based on ca-ttl

      (finally
        (fs/delete expected-cert-path)))))

(deftest get-certificate-revocation-list-test
  (testing "`get-certificate-revocation-list` returns a valid CRL file."
    (let [crl (-> (get-certificate-revocation-list cacrl)
                  StringReader.
                  utils/pem->crl)]
      (assert-issuer crl "CN=Puppet CA: localhost"))))

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

  (deftest initialize-ca!-test
    (try
      (initialize-ca! ca-settings 512)

      (testing "Generated SSL file"
        (doseq [file (vals cadir-contents)]
          (testing file
            (is (fs/exists? file)))))

      (testing "cacrl"
        (let [crl (-> cadir-contents :cacrl utils/pem->crl)]
          (assert-issuer crl "CN=test ca")))

      (testing "cacert"
        (let [cert (-> cadir-contents :cacert utils/pem->cert)]
          (is (utils/certificate? cert))
          (assert-subject cert "CN=test ca")
          (assert-issuer cert "CN=test ca")))

      (testing "cakey"
        (let [key (-> cadir-contents :cakey utils/pem->private-key)]
          (is (utils/private-key? key))
          (is (= 512 (utils/keylength key)))))

      (testing "capub"
        (let [key (-> cadir-contents :capub utils/pem->public-key)]
          (is (utils/public-key? key))
          (is (= 512 (utils/keylength key)))))

      (finally
        (fs/delete-dir cadir))))

  (deftest initialize-master!-test
    (try
      (initialize-master! ssldir-contents "master" "Puppet CA: localhost"
                          (utils/pem->private-key cakey)
                          (utils/pem->cert cacert)
                          512)

      (testing "Generated SSL file"
        (doseq [file (vals ssldir-contents)]
          (testing file
            (is (fs/exists? file)))))

      (testing "hostcert"
        (let [cert (-> ssldir-contents :hostcert utils/pem->certs first)]
          (is (utils/certificate? cert))
          (assert-subject cert "CN=master")
          (assert-issuer cert "CN=Puppet CA: localhost")))

      (testing "localcacert"
        (let [cacert (-> ssldir-contents :localcacert utils/pem->certs first)]
          (is (utils/certificate? cacert))
          (assert-subject cacert "CN=Puppet CA: localhost")
          (assert-issuer cacert "CN=Puppet CA: localhost")))

      (testing "hostprivkey"
        (let [key (-> ssldir-contents :hostprivkey utils/pem->private-key)]
          (is (utils/private-key? key))
          (is (= 512 (utils/keylength key)))))

      (testing "hostpubkey"
        (let [key (-> ssldir-contents :hostpubkey utils/pem->public-key)]
          (is (utils/public-key? key))
          (is (= 512 (utils/keylength key)))))

      (finally
        (fs/delete-dir ssldir))))

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
            (is (= expected (-> cadir-contents :cakey
                                utils/pem->private-key utils/keylength)))
            (is (= expected (-> cadir-contents :capub
                                utils/pem->public-key utils/keylength)))
            (is (= expected (-> ssldir-contents :hostprivkey
                                utils/pem->private-key utils/keylength)))
            (is (= expected (-> ssldir-contents :hostpubkey
                                utils/pem->public-key utils/keylength)))
            (finally
              (fs/delete-dir ssldir))))))))
