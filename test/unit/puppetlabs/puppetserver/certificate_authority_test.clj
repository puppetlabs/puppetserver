(ns puppetlabs.puppetserver.certificate-authority-test
  (:import (java.io StringReader
                    StringWriter
                    ByteArrayInputStream
                    ByteArrayOutputStream)
           (com.puppetlabs.ssl_utils SSLUtils)
           (java.security PublicKey MessageDigest)
           (java.util Date)
           (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks ReentrantReadWriteLock)
           (org.joda.time DateTime Period)
           (org.bouncycastle.asn1.x509 SubjectPublicKeyInfo))
  (:require [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.trapperkeeper.testutils.logging :refer [logged?] :as logutils]
            [puppetlabs.ssl-utils.core :as utils]
            [puppetlabs.ssl-utils.simple :as simple]
            [puppetlabs.services.ca.ca-testutils :as testutils]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.kitchensink.file :as ks-file]
            [slingshot.test :refer :all]
            [schema.test :as schema-test]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [me.raynes.fs :as fs]))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test Data

(def test-resources-dir "./dev-resources/puppetlabs/puppetserver/certificate_authority_test")
(def confdir (str test-resources-dir "/master/conf"))
(def ssldir (str confdir "/ssl"))
(def cadir (str confdir "/ca"))
(def cacert (str cadir "/ca_crt.pem"))
(def cakey (str cadir "/ca_key.pem"))
(def capub (str cadir "/ca_pub.pem"))
(def cacrl (str cadir "/ca_crl.pem"))
(def csrdir (str cadir "/requests"))
(def signeddir (str cadir "/signed"))
(def test-pems-dir (str test-resources-dir "/pems"))
(def autosign-confs-dir (str test-resources-dir "/autosign_confs"))
(def autosign-exes-dir (str test-resources-dir "/autosign_exes"))
(def csr-attributes-dir (str test-resources-dir "/csr_attributes"))
(def bundle-dir (str test-pems-dir "/bundle"))
(def bundle-cadir (str bundle-dir "/ca"))

(defn test-pem-file
  [pem-file-name]
  (str test-pems-dir "/" pem-file-name))

(defn autosign-conf-file
  [autosign-conf-file-name]
  (str autosign-confs-dir "/" autosign-conf-file-name))

(defn autosign-exe-file
  [autosign-exe-file-name]
  (ks/absolute-path (str autosign-exes-dir "/" autosign-exe-file-name)))

(defn csr-attributes-file
  [csr-attributes-file-name]
  (str csr-attributes-dir "/" csr-attributes-file-name))

(def all-perms
  (for [r "r-"
        w "w-"
        x "x-"]
    (str r w x)))

(def attribute-file-extensions
  "These are the extensions defined in the test fixture csr_attributes.yaml,
  used in this test namespace."
  [{:oid "1.3.6.1.4.1.34380.1.1.1"
    :critical false
    :value "ED803750-E3C7-44F5-BB08-41A04433FE2E"}
   {:oid "1.3.6.1.4.1.34380.1.1.1.4"
    :critical false
    :value "I am undefined but still work"}
   {:oid "1.3.6.1.4.1.34380.1.1.2"
    :critical false
    :value "thisisanid"}
   {:oid "1.3.6.1.4.1.34380.1.1.3"
    :critical false
    :value "my_ami_image"}
   {:oid "1.3.6.1.4.1.34380.1.1.4"
    :critical false
    :value "342thbjkt82094y0uthhor289jnqthpc2290"}
   {:oid "1.3.6.1.4.1.34380.1.1.5"
    :critical false
    :value "center"}
   {:oid "1.3.6.1.4.1.34380.1.1.6"
    :critical false
    :value "product"}
   {:oid "1.3.6.1.4.1.34380.1.1.7"
    :critical false
    :value "project"}
   {:oid "1.3.6.1.4.1.34380.1.1.8"
    :critical false
    :value "application"}
   {:oid "1.3.6.1.4.1.34380.1.1.9"
    :critical false
    :value "service"}
   {:oid "1.3.6.1.4.1.34380.1.1.10"
    :critical false
    :value "employee"}
   {:oid "1.3.6.1.4.1.34380.1.1.11"
    :critical false
    :value "created"}
   {:oid "1.3.6.1.4.1.34380.1.1.12"
    :critical false
    :value "environment"}
   {:oid "1.3.6.1.4.1.34380.1.1.13"
    :critical false
    :value "role"}
   {:oid "1.3.6.1.4.1.34380.1.1.14"
    :critical false
    :value "version"}
   {:oid "1.3.6.1.4.1.34380.1.1.15"
    :critical false
    :value "deparment"}
   {:oid "1.3.6.1.4.1.34380.1.1.16"
    :critical false
    :value "cluster"}
   {:oid "1.3.6.1.4.1.34380.1.1.17"
    :critical false
    :value "provisioner"}])
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn tmp-whitelist! [& lines]
  (let [whitelist (ks/temp-file)]
    (doseq [line lines]
      (spit whitelist (str line "\n") :append true))
    (str whitelist)))

(def empty-stream (ByteArrayInputStream. (.getBytes "")))

(defn csr-stream [subject]
  (io/input-stream (ca/path-to-cert-request csrdir subject)))

(defn write-to-stream [o]
  (let [s (ByteArrayOutputStream.)]
    (utils/obj->pem! o s)
    (-> s .toByteArray ByteArrayInputStream.)))

(defn assert-autosign [whitelist subject]
  (testing subject
    (is (true? (ca/autosign-csr? whitelist subject empty-stream)))))

(defn assert-no-autosign [whitelist subject]
  (testing subject
    (is (false? (ca/autosign-csr? whitelist subject empty-stream)))))

(defn contains-ext?
  "Does the provided extension list contain an extensions with the given OID."
  [ext-list oid]
  (> (count (filter #(= oid (:oid %)) ext-list)) 0))

;; TODO copied from jvm-ssl-utils testutils. That lib should be updated
;; to expose its test jar so we can use this directly instead.
(defn pubkey-sha1
  "Gets the SHA-1 digest of the raw bytes of the provided publickey."
  [pub-key]
  {:pre [(utils/public-key? pub-key)]
   :post [(vector? %)
          (every? integer? %)]}
  (let [bytes   (-> ^PublicKey
                    pub-key
                    .getEncoded
                    SubjectPublicKeyInfo/getInstance
                    .getPublicKeyData
                    .getBytes)]
    (vec (.digest (MessageDigest/getInstance "SHA1") bytes))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest symlink-cadir-test
  (testing "does not symlink if custom cadir"
    (let [tmpdir (ks/temp-dir)
          cadir (str tmpdir "/foo/bar")
          ssldir (str tmpdir "puppet/ssl")]
      (fs/mkdirs cadir)
      (fs/mkdirs ssldir)
      (ca/symlink-cadir cadir)
      (is (not (fs/exists? (str ssldir "/ca"))))))
  (testing "symlinks correctly and removes existing old-cadir if needed"
    (let [tmpdir (ks/temp-dir)
          cadir (str tmpdir "/puppetserver/ca")
          ssldir (str tmpdir "/puppet/ssl")
          old-cadir (str ssldir "/ca")]
      (fs/mkdirs ssldir)
      (fs/mkdirs cadir)
      (ca/symlink-cadir cadir)
      (is (fs/link? old-cadir))
      (let [target (-> old-cadir fs/read-sym-link str)]
        (is (= target cadir))))))

(deftest validate-settings-test
  (testing "invalid ca-ttl is rejected"
    (let [settings (assoc
                     (testutils/ca-settings cadir)
                     :ca-ttl
                     (+ ca/max-ca-ttl 1))]
      (is (thrown-with-msg? IllegalStateException #"ca_ttl must have a value below"
                            (ca/validate-settings! settings)))))

  (testing "warns if :client-whitelist is set in c-a.c-s section"
    (let [settings (assoc-in
                     (testutils/ca-settings cadir)
                     [:access-control :certificate-status :client-whitelist]
                     ["whitelist"])]
      (logutils/with-test-logging
        (ca/validate-settings! settings)
        (is (logutils/logged? #"Remove these settings and create" :warn)))))

  (testing "warns if :authorization-required is overridden in c-a.c-s section"
    (let [settings (assoc-in
                     (testutils/ca-settings cadir)
                     [:access-control
                      :certificate-status
                      :authorization-required]
                     false)]
      (logutils/with-test-logging
        (ca/validate-settings! settings)
        (is (logutils/logged? #"Remove these settings and create" :warn)))))

  (testing "warns if :client-whitelist is set incorrectly"
    (let [settings (assoc-in
                     (testutils/ca-settings cadir)
                     [:access-control :certificate-status :client-whitelist]
                     [])]
      (logutils/with-test-logging
        (ca/validate-settings! settings)
        (is (logutils/logged?
              #"remove the 'certificate-authority' configuration"
              :warn))))))

(deftest get-certificate-test
  (testing "returns CA certificate when subject is 'ca'"
    (let [actual   (ca/get-certificate "ca" cacert signeddir)
          expected (slurp cacert)]
      (is (= expected actual))))

  (testing "returns localhost certificate when subject is 'localhost'"
    (let [localhost-cert (ca/get-certificate "localhost" cacert signeddir)
          expected       (slurp (ca/path-to-cert signeddir "localhost"))]
      (is (= expected localhost-cert))))

  (testing "returns nil when certificate not found for subject"
    (is (nil? (ca/get-certificate "not-there" cacert signeddir)))))

(deftest get-certificate-request-test
  (testing "returns certificate request for subject"
    (let [cert-req (ca/get-certificate-request "test-agent" csrdir)
          expected (slurp (ca/path-to-cert-request csrdir "test-agent"))]
      (is (= expected cert-req))))

  (testing "returns nil when certificate request not found for subject"
    (is (nil? (ca/get-certificate-request "not-there" csrdir)))))

(deftest autosign-csr?-test
  (testing "boolean values"
    (is (true? (ca/autosign-csr? true "unused" empty-stream)))
    (is (false? (ca/autosign-csr? false "unused" empty-stream))))

  (testing "whitelist"
    (testing "autosign is false when whitelist doesn't exist"
      (is (false? (ca/autosign-csr? "Foo/conf/autosign.conf" "doubleagent"
                                    empty-stream))))

    (testing "exact certnames"
      (doto (tmp-whitelist! "foo"
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
      (doto (tmp-whitelist! "*.red"
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
      (doto (tmp-whitelist! "*")
        (assert-autosign "foo")
        (assert-autosign "BAR")
        (assert-autosign "baz-buz.")
        (assert-autosign "0.qux.1.xuq")
        (assert-autosign "AB=foo,BC=bar,CD=rab,DE=oof,EF=1a2b3d")))

    (testing "ignores comments and blank lines"
      (doto (tmp-whitelist! "#foo"
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
        (let [whitelist (tmp-whitelist! "foo"
                                        invalid-line
                                        "qux")]
          (assert-autosign whitelist "foo")
          (logutils/with-test-logging
            (assert-no-autosign whitelist invalid-line)
            (is (logged?
                 (re-pattern (format "Invalid pattern '%s' found in %s"
                                     invalid-line whitelist))))
            (assert-autosign whitelist "qux")))))

    (testing "sample file that covers everything"
      (logutils/with-test-logging
        (doto (autosign-conf-file "autosign-whitelist.conf")
          (assert-no-autosign "aaa")
          (assert-autosign "bbb123")
          (assert-autosign "one_2.red")
          (assert-autosign "1.blacK.6")
          (assert-no-autosign "black.white")
          (assert-no-autosign "coffee")
          (assert-no-autosign "coffee#tea")
          (assert-autosign "qux"))))))

(deftest autosign-csr?-ruby-exe-test
  (let [executable (autosign-exe-file "ruby-autosign-executable")
        csr-fn #(csr-stream "test-agent")
        ruby-load-path jruby-testutils/ruby-load-path
        ruby-gem-path jruby-testutils/gem-path]

    (testing "stdout is added to master's log at debug level"
      (logutils/with-test-logging
        (ca/autosign-csr? executable "test-agent" (csr-fn) ruby-load-path ruby-gem-path)
        (is (logged? #"print to stdout" :debug))))

    (testing "stderr is added to master's log at warn level"
      (logutils/with-test-logging
       (ca/autosign-csr? executable "test-agent" (csr-fn) ruby-load-path ruby-gem-path)
       (is (logged? #"generated output to stderr: print to stderr" :warn))))

    (testing "non-zero exit-code generates a log entry at warn level"
      (logutils/with-test-logging
       (ca/autosign-csr? executable "foo" (csr-fn) ruby-load-path ruby-gem-path)
       (is (logged? #"rejected certificate 'foo'" :warn))))

    (testing "Ruby load path is configured and contains Puppet"
      (logutils/with-test-logging
        (ca/autosign-csr? executable "test-agent" (csr-fn) ruby-load-path ruby-gem-path)
        (is (logged? #"Ruby load path configured properly"))))

    (testing "subject is passed as argument and CSR is provided on stdin"
      (logutils/with-test-logging
        (ca/autosign-csr? executable "test-agent" (csr-fn) ruby-load-path ruby-gem-path)
        (is (logged? #"subject: test-agent"))
        (is (logged? #"CSR for: test-agent"))))

    (testing "only exit code 0 results in autosigning"
      (logutils/with-test-logging
        (is (true? (ca/autosign-csr? executable "test-agent" (csr-fn) ruby-load-path ruby-gem-path)))
        (is (false? (ca/autosign-csr? executable "foo" (csr-fn) ruby-load-path ruby-gem-path)))))))

(deftest autosign-csr?-bash-exe-test
  (let [executable (autosign-exe-file "bash-autosign-executable")
        csr-fn #(csr-stream "test-agent")]

    (testing "stdout is added to master's log at debug level"
      (logutils/with-test-logging
        (ca/autosign-csr? executable "test-agent" (csr-fn))
        (is (logged? #"print to stdout" :debug))))

    (testing "stderr is added to master's log at warn level"
      (logutils/with-test-logging
       (ca/autosign-csr? executable "test-agent" (csr-fn))
       (is (logged? #"generated output to stderr: print to stderr" :warn))))

    (testing "non-zero exit-code generates a log entry at warn level"
      (logutils/with-test-logging
       (ca/autosign-csr? executable "foo" (csr-fn))
       (is (logged? #"rejected certificate 'foo'" :warn))))

    (testing "subject is passed as argument and CSR is provided on stdin"
      (logutils/with-test-logging
        (ca/autosign-csr? executable "test-agent" (csr-fn))
        (is (logged? #"subject: test-agent"))
        (is (logged? #"-----BEGIN CERTIFICATE REQUEST-----"))))

    (testing "only exit code 0 results in autosigning"
      (logutils/with-test-logging
        (is (true? (ca/autosign-csr? executable "test-agent" (csr-fn))))
        (is (false? (ca/autosign-csr? executable "foo" (csr-fn))))))))

(deftest save-certificate-request!-test
  (testing "requests are saved to disk"
    (let [csrdir   (:csrdir (testutils/ca-sandbox! cadir))
          csr      (utils/pem->csr (ca/path-to-cert-request csrdir "test-agent"))
          path     (ca/path-to-cert-request csrdir "foo")]
      (is (false? (fs/exists? path)))
      (ca/save-certificate-request! "foo" csr csrdir)
      (is (true? (fs/exists? path)))
      (is (= (ca/get-certificate-request csrdir "foo")
             (ca/get-certificate-request csrdir "test-agent"))))))

(deftest autosign-certificate-request!-test
  (let [now                (time/epoch)
        two-years          (* 60 60 24 365 2)
        settings           (-> (testutils/ca-sandbox! cadir)
                               (assoc :ca-ttl two-years))
        csr                (-> (:csrdir settings)
                               (ca/path-to-cert-request "test-agent")
                               (utils/pem->csr))
        expected-cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
    ;; Fix the value of "now" so we can reliably test the dates
    (time/do-at now
      (ca/autosign-certificate-request! "test-agent" csr settings (constantly nil)))

    (testing "requests are autosigned and saved to disk"
      (is (fs/exists? expected-cert-path)))

    (let [cert (utils/pem->cert expected-cert-path)]
      (testing "The subject name on the agent's cert"
        (testutils/assert-subject cert "CN=test-agent"))

      (testing "The cert is issued by the name on the CA's cert"
        (testutils/assert-issuer cert "CN=Puppet CA: localhost"))

      (testing "certificate has not-before/not-after dates based on $ca-ttl"
        (let [not-before (time-coerce/from-date (.getNotBefore cert))
              not-after (time-coerce/from-date (.getNotAfter cert))]
          (testing "not-before is 1 day before now"
            (is (= (time/minus now (time/days 1)) not-before)))
          (testing "not-after is 2 years from now"
            (is (= (time/plus now (time/years 2)) not-after))))))))

(deftest autosign-without-capub
  (testing "The CA public key file is not necessary to autosign"
    (let [settings  (testutils/ca-sandbox! cadir)
          csr       (-> (:csrdir settings)
                        (ca/path-to-cert-request "test-agent")
                        (utils/pem->csr))
          cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (fs/delete (:capub settings))
      (ca/autosign-certificate-request! "test-agent" csr settings (constantly nil))
      (is (true? (fs/exists? cert-path)))
      (let [cert  (utils/pem->cert cert-path)
            capub (-> (:cacert settings)
                      (utils/pem->cert)
                      (.getPublicKey))]
        (is (nil? (.verify cert capub)))))))

(deftest autosign-as-intermediate-ca
  (testing "The CA certificate file can be a bundle of certs"
    (let [settings  (testutils/ca-sandbox! bundle-cadir)
          csr       (-> (:csrdir settings)
                        (ca/path-to-cert-request "test-agent")
                        (utils/pem->csr))
          cert-path (ca/path-to-cert (:signeddir settings) "test-agent")]
      (ca/autosign-certificate-request! "test-agent" csr settings (constantly nil))
      (is (true? (fs/exists? cert-path)))
      (let [cert  (utils/pem->cert cert-path)
            capub (-> (:cacert settings)
                      (utils/pem->certs)
                      (first)
                      (.getPublicKey))]
        (is (nil? (.verify cert capub)))))))

(deftest revoke-without-capub
  (testing "The CA public key file is not necessary to revoke"
    (let [settings (testutils/ca-sandbox! cadir)
          cert     (-> (:signeddir settings)
                       (ca/path-to-cert "localhost")
                       (utils/pem->cert))
          revoked? (fn [cert]
                     (-> (:cacrl settings)
                         (utils/pem->crl)
                         (utils/revoked? cert)))]
      (fs/delete (:capub settings))
      (is (false? (revoked? cert)))
      (ca/revoke-existing-certs! settings ["localhost"] (constantly nil))
      (is (true? (revoked? cert))))))

(deftest revoke-as-intermediate-ca
  (testing "The CA certificate file can be a bundle when revoking a certificate"
    (let [settings (testutils/ca-sandbox! bundle-cadir)
          cert     (-> (:signeddir settings)
                       (ca/path-to-cert "localhost")
                       (utils/pem->cert))
          ca-cert (utils/pem->ca-cert (:cacert settings) (:cakey settings))
          revoked? (fn [cert]
                     (-> (:cacrl settings)
                         (utils/pem->ca-crl ca-cert)
                         (utils/revoked? cert)))]
      (is (false? (revoked? cert)))
      (ca/revoke-existing-certs! settings ["localhost"] (constantly nil))
      (is (true? (revoked? cert))))))

(deftest revoke-multiple-certs
  (testing "The revocation function can accept a list of certs to revoke"
    (let [settings (testutils/ca-sandbox! cadir)
          cert1 (-> (:signeddir settings)
                    (ca/path-to-cert "localhost")
                    (utils/pem->cert))
          cert2 (-> (:signeddir settings)
                    (ca/path-to-cert "test_cert")
                    (utils/pem->cert))
          revoked? (fn [cert]
                     (-> (:cacrl settings)
                         (utils/pem->crl)
                         (utils/revoked? cert)))]
      (is (false? (revoked? cert1)))
      (is (false? (revoked? cert2)))
      (ca/revoke-existing-certs! settings ["localhost" "test_cert"] (constantly nil))
      (is (true? (revoked? cert1)))
      (is (true? (revoked? cert2))))))

(deftest filter-already-revoked-serials-test
  (let [lock (new ReentrantReadWriteLock)
        descriptor "test-crl"
        timeout 1
        crl (-> (ca/get-certificate-revocation-list cacrl lock descriptor timeout)
                StringReader.
                utils/pem->crl)]
   (testing "Return an empty vector when all supplied serials are already in CRL"
     (let [test-serial (vec [4])
           filtered-serial (ca/filter-already-revoked-serials test-serial crl)]
       (is (empty? filtered-serial))))

   (testing "Return a vector of serials not yet in CRL"
     (let [test-serial (vec [1 2 3 4])
           filtered-serial (ca/filter-already-revoked-serials test-serial crl)]
       (is (true? (= (sort filtered-serial) [1 2 3])))))

   (testing "Deduplicates the vector of serials to be revoked"
     (let [test-serial (vec [1 1 2 2 3 3])
           filtered-serial (ca/filter-already-revoked-serials test-serial crl)]
       (is (apply distinct? filtered-serial))))))

(deftest get-certificate-revocation-list-test
  (testing "`get-certificate-revocation-list` returns a valid CRL file."
    (let [lock (new ReentrantReadWriteLock)
          descriptor "test-crl"
          timeout 1
          crl (-> (ca/get-certificate-revocation-list cacrl lock descriptor timeout)
                  StringReader.
                  utils/pem->crl)]
      (testutils/assert-issuer crl "CN=Puppet CA: localhost"))))

(deftest update-crls-test
  (let [update-crl-fixture-dir (str test-resources-dir "/update_crls/")
        cert-chain-path (str update-crl-fixture-dir "ca_crt.pem")
        crl-path (str update-crl-fixture-dir "ca_crl.pem")
        crl-backup-path (str update-crl-fixture-dir "ca_crl.pem.bak")]
    (logutils/with-test-logging
     (testing "a single newer CRL is used"
       (let [new-crl-path (str update-crl-fixture-dir "new_root_crl.pem")
             incoming-crls (utils/pem->crls new-crl-path)]
         (testutils/with-backed-up-crl crl-path crl-backup-path
           (ca/update-crls incoming-crls crl-path cert-chain-path)
           (let [old-crls (utils/pem->crls crl-backup-path)
                 new-crls (utils/pem->crls crl-path)
                 old-number (utils/get-crl-number (last old-crls))
                 new-number (utils/get-crl-number (last new-crls))]
             (is (> new-number old-number))))))
     (testing "the newest CRL given is used"
       (let [multiple-new-crls-path (str update-crl-fixture-dir "multiple_new_root_crls.pem")
             incoming-crls (utils/pem->crls multiple-new-crls-path)]
         (testutils/with-backed-up-crl crl-path crl-backup-path
           (ca/update-crls incoming-crls crl-path cert-chain-path)
           (let [old-crls (utils/pem->crls crl-backup-path)
                 new-crls (utils/pem->crls crl-path)
                 old-number (utils/get-crl-number (last old-crls))
                 new-number (utils/get-crl-number (last new-crls))]
             (is (> new-number old-number))
             (is (= 10 new-number))))))
     (testing "multiple newer CRLs are used"
       (let [three-cert-path (str update-crl-fixture-dir "three_cert_chain.pem")
             three-crl-path (str update-crl-fixture-dir "three_crl.pem")
             three-newer-crls-path (str update-crl-fixture-dir "three_newer_crl_chain.pem")
             incoming-crls (utils/pem->crls three-newer-crls-path)]
         (testutils/with-backed-up-crl three-crl-path crl-backup-path
           (ca/update-crls incoming-crls three-crl-path three-cert-path)
           (let [old-crls (utils/pem->crls crl-backup-path)
                 new-crls (utils/pem->crls three-crl-path)]
             (is (> (utils/get-crl-number (.get new-crls 2))
                    (utils/get-crl-number (.get old-crls 2))))
             (is (> (utils/get-crl-number (.get new-crls 1))
                    (utils/get-crl-number (.get old-crls 1))))
             ;; Leaf CRL is never replaced
             (is (= (utils/get-crl-number (.get new-crls 0))
                    (utils/get-crl-number (.get old-crls 0))))))))
     (testing "unrelated CRLs are ignored while newer relevant CRLs are used"
       (let [new-and-unrelated-crls-path (str update-crl-fixture-dir "new_crls_and_unrelated_crls.pem")
             incoming-crls (utils/pem->crls new-and-unrelated-crls-path)]
         (testutils/with-backed-up-crl crl-path crl-backup-path
           (ca/update-crls incoming-crls crl-path cert-chain-path)
           (let [old-crls (utils/pem->crls crl-backup-path)
                 new-crls (utils/pem->crls crl-path)]
               (is (> (utils/get-crl-number (last new-crls))
                      (utils/get-crl-number (last old-crls))))
               ;; Leaf CRL is never replaced
               (is (= (utils/get-crl-number (first new-crls))
                      (utils/get-crl-number (first old-crls))))
               (testing "CRLs with same issuer name but different auth keys are not used"
                 (is (= (utils/get-extension-value (last new-crls) utils/authority-key-identifier-oid)
                        (utils/get-extension-value (last old-crls) utils/authority-key-identifier-oid)))
                 (is (= (utils/get-extension-value (first new-crls) utils/authority-key-identifier-oid)
                        (utils/get-extension-value (first old-crls) utils/authority-key-identifier-oid))))))))
     (testing "all unrelated CRLs are ignored entirely"
       (let [unrelated-crls-path (str update-crl-fixture-dir "unrelated_crls.pem")
             incoming-crls (utils/pem->crls unrelated-crls-path)]
         (testutils/with-backed-up-crl crl-path crl-backup-path
           (ca/update-crls incoming-crls crl-path cert-chain-path)
           (let [old-crls (utils/pem->crls crl-backup-path)
                 new-crls (utils/pem->crls crl-path)]
             (is (= (count old-crls) (count new-crls)))
             (is (= (set old-crls) (set new-crls)))))))
     (testing "older CRLs are ignored"
       (let [new-root-crl-chain-path (str update-crl-fixture-dir "chain_with_new_root.pem")
             old-root-crl-path (str update-crl-fixture-dir "old_root_crl.pem")
             incoming-crls (utils/pem->crls old-root-crl-path)]
         (testutils/with-backed-up-crl new-root-crl-chain-path crl-backup-path
           (ca/update-crls incoming-crls new-root-crl-chain-path cert-chain-path)
           (let [old-crls (utils/pem->crls crl-backup-path)
                 new-crls (utils/pem->crls new-root-crl-chain-path)]
               (is (= (set new-crls) (set old-crls)))))))

     (let [multiple-newest-crls-path (str update-crl-fixture-dir "multiple_newest_root_crls.pem")
           delta-crl-path (str test-resources-dir "/update_crls/delta_crl.pem")
           missing-auth-id-crl-path (str test-resources-dir "/update_crls/missing_auth_id_crl.pem")
           bad-inputs-and-error-msgs {multiple-newest-crls-path #"Could not determine newest CRL."
                                      delta-crl-path #"Cannot support delta CRL."
                                      missing-auth-id-crl-path #"CRLs do not have an authority key"}]
       (doseq [[path error-message] bad-inputs-and-error-msgs]
         (testing (str "CRLs from " path " are rejected")
           (let [incoming-crls (utils/pem->crls path)]
             (testutils/with-backed-up-crl crl-path crl-backup-path
               (is (thrown-with-msg? IllegalArgumentException error-message
                                     (ca/update-crls incoming-crls crl-path cert-chain-path)))
               (let [old-crls (utils/pem->crls crl-backup-path)
                     new-crls (utils/pem->crls crl-path)]
                 (is (= (set old-crls) (set new-crls))))))))))))

(deftest initialize!-test
  (let [settings (testutils/ca-settings (ks/temp-dir))]

    (ca/initialize! settings)

    (testing "Generated SSL file"
      (doseq [file (-> (ca/settings->cadir-paths settings)
                       (select-keys (ca/required-ca-files
                                      (:enable-infra-crl settings)))
                       (vals))]
        (testing file
          (is (fs/exists? file)))))

    (testing "cacrl"
      (let [crl (-> settings :cacrl utils/pem->crl)]
        (testutils/assert-issuer crl "CN=test ca")
        (testing "has CRLNumber and AuthorityKeyIdentifier extensions"
          (is (not (nil? (utils/get-extension-value crl utils/crl-number-oid))))
          (is (not (nil? (utils/get-extension-value crl utils/authority-key-identifier-oid)))))))

    (testing "cacert"
      (let [cert (-> settings :cacert utils/pem->cert)]
        (is (utils/certificate? cert))
        (testutils/assert-subject cert "CN=test ca")
        (testutils/assert-issuer cert "CN=test ca")
        (testing "has at least one expected extension - key usage"
          (let [key-usage (utils/get-extension-value cert "2.5.29.15")]
            (is (= #{:key-cert-sign :crl-sign} key-usage))))
        (testing "does not have any SANs"
          (is (nil? (utils/get-extension-value cert ca/subject-alt-names-oid))))
        (testing "authority key identifier is SHA of public key"
          (let [ca-pub-key (-> settings :capub utils/pem->public-key)
                pub-key-sha (pubkey-sha1 ca-pub-key)]
            (is (= pub-key-sha
                   (:key-identifier (utils/get-extension-value cert utils/authority-key-identifier-oid))))))))

    (testing "cakey"
      (let [key (-> settings :cakey utils/pem->private-key)]
        (is (utils/private-key? key))
        (is (= 512 (utils/keylength key)))))

    (testing "capub"
      (let [key (-> settings :capub utils/pem->public-key)]
        (is (utils/public-key? key))
        (is (= 512 (utils/keylength key)))))

    (testing "cert-inventory"
      (is (fs/exists? (:cert-inventory settings))))

    (testing "serial"
      (is (fs/exists? (:serial settings))))

    (testing "allow-auto-renewal"
      (is (= false (:allow-auto-renewal settings))))

    (testing "auto-renewal-cert-ttl"
      (is (= "90d" (:auto-renewal-cert-ttl settings))))

    (testing "Does not replace files if they all exist"
      (let [files (-> (ca/settings->cadir-paths (assoc settings :enable-infra-crl false))
                      (dissoc :csrdir :signeddir :cadir)
                      (vals))]
        (doseq [f files] (spit f "testable string"))
        (ca/initialize! settings)
        (doseq [f files] (is (= "testable string" (slurp f))
                             (str "File " f " was replaced")))))))

(deftest initialize!-test-with-keylength-in-settings
  (let [settings (assoc (testutils/ca-settings (ks/temp-dir)) :keylength 768)]
    (ca/initialize! settings)
    (testing "cakey with keylength"
      (let [key (-> settings :cakey utils/pem->private-key)]
        (is (utils/private-key? key))
        (is (= 768 (utils/keylength key)))))

    (testing "capub with keylength"
      (let [key (-> settings :capub utils/pem->public-key)]
        (is (utils/public-key? key))
        (is (= 768 (utils/keylength key)))))))

(deftest ca-fail-fast-test
  (testing "Directories not required but are created if absent"
    (doseq [dir [:signeddir :csrdir]]
      (testing dir
        (let [settings (testutils/ca-sandbox! cadir)]
          (fs/delete-dir (get settings dir))
          (is (nil? (ca/initialize! settings)))
          (is (true? (fs/exists? (get settings dir))))))))

  (testing "CA public key not required"
    (let [settings (testutils/ca-sandbox! cadir)]
      (fs/delete (:capub settings))
      (is (nil? (ca/initialize! settings)))))

  (testing "Exception is thrown when required file is missing"
    (doseq [file (ca/required-ca-files true)]
      (testing file
        (let [settings (assoc (testutils/ca-sandbox! cadir) :enable-infra-crl true)
              path     (get settings file)]
          (fs/delete path)
          (is (thrown-with-msg?
               IllegalStateException
               (re-pattern (str "Missing:\n" path))
               (ca/initialize! settings)))))))

  (testing "The CA private key has its permissions properly reset when :manage-internal-file-permissions is true."
    (let [settings (testutils/ca-sandbox! cadir)]
      (ks-file/set-perms (:cakey settings) "rw-r--r--")
      (logutils/with-test-logging
        (ca/initialize! settings)
        (is (logged? #"/ca/ca_key.pem' was found to have the wrong permissions set as 'rw-r--r--'. This has been corrected to 'rw-r-----'."))
        (is (= ca/private-key-perms (ks-file/get-perms (:cakey settings)))))))

  (testing "The CA private key's permissions are not reset if :manage-internal-file-permissions is false."
    (let [perms "rw-r--r--"
          settings (assoc (testutils/ca-sandbox! cadir)
                     :manage-internal-file-permissions false)]
      (ks-file/set-perms (:cakey settings) perms)
      (ca/initialize! settings)
      (is (= perms (ks-file/get-perms (:cakey settings)))))))

(deftest retrieve-ca-cert!-test
  (testing "CA file copied when it doesn't already exist"
    (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
          settings    (testutils/master-settings tmp-confdir)
          ca-settings (testutils/ca-settings (str tmp-confdir "/ca"))
          cacert      (:cacert ca-settings)
          localcacert (:localcacert settings)
          cacert-text (slurp cacert)]

      (testing "Copied cacert to localcacert when localcacert not present"
        (ca/retrieve-ca-cert! cacert localcacert)
        (is (= (slurp localcacert) cacert-text)
            (str "Unexpected content for localcacert: " localcacert)))

      (testing "Doesn't copy cacert over localcacert when different localcacert present"
        (let [localcacert-contents "12345"]
          (spit (:localcacert settings) localcacert-contents)
          (ca/retrieve-ca-cert! cacert localcacert)
          (is (= (slurp localcacert) localcacert-contents)
              (str "Unexpected content for localcacert: " localcacert))))

      (testing "Throws exception if no localcacert and no cacert to copy"
        (fs/delete localcacert)
        (let [copy (fs/copy cacert (ks/temp-file))]
          (fs/delete cacert)
          (is (thrown? IllegalStateException
                       (ca/retrieve-ca-cert! cacert localcacert))
              "No exception thrown even though no file existed for copying")
          (fs/copy copy cacert))))))

(deftest retrieve-ca-crl!-test
  (testing "CRL file copied when it doesn't already exist"
    (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
          settings    (testutils/master-settings tmp-confdir)
          ca-settings (testutils/ca-settings (str tmp-confdir "/ca"))
          cacrl       (:cacrl ca-settings)
          hostcrl     (:hostcrl settings)
          cacrl-text (slurp cacrl)]

      (testing "Copied cacrl to hostcrl when hostcrl not present"
        (ca/retrieve-ca-crl! cacrl hostcrl)
        (is (= (slurp hostcrl) cacrl-text)
            (str "Unexpected content for hostcrl: " hostcrl)))

      (testing "Copied cacrl to hostcrl when different hostcrl present"
        (spit (:hostcrl settings) "12345")
        (ca/retrieve-ca-crl! cacrl hostcrl)
        (is (= (slurp hostcrl) cacrl-text)
            (str "Unexpected content for hostcrl: " hostcrl)))

      (testing "Doesn't throw exception or create dummy file if no hostcrl and no cacrl to copy"
        (fs/delete hostcrl)
        (let [copy (fs/copy cacrl (ks/temp-file))]
          (fs/delete cacrl)
          (is (not (fs/exists? hostcrl))
              "hostcrl file present even though no file existed for copying")
          (fs/copy copy cacrl))))))

(deftest initialize-master-ssl!-test
  (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
        settings    (-> (testutils/master-settings tmp-confdir "master")
                        (assoc :dns-alt-names "onefish,twofish"))
        ca-settings (testutils/ca-settings (str tmp-confdir "/ca"))]

    (ca/retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))
    (ca/retrieve-ca-crl! (:cacrl ca-settings) (:hostcrl settings))
    (ca/initialize-master-ssl! settings "master" ca-settings)

    (testing "Generated SSL file"
      (doseq [file (vals (ca/settings->ssldir-paths settings))]
        (testing file
          (is (fs/exists? file)))))

    (testing "hostcert"
      (let [hostcert (-> settings :hostcert utils/pem->cert)]
        (is (utils/certificate? hostcert))
        (testutils/assert-subject hostcert "CN=master")
        (testutils/assert-issuer hostcert "CN=Puppet CA: localhost")

        (testing "has alt names extension"
          (let [dns-alt-names (utils/get-subject-dns-alt-names hostcert)]
            (is (= #{"master" "onefish" "twofish"} (set dns-alt-names))
                "The Subject Alternative Names extension should contain the
                 master's actual hostname and the hostnames in $dns-alt-names")))

        (testing "has CLI auth extension"
          (let [cli-auth-ext (utils/get-extension-value hostcert ca/cli-auth-oid)]
            (is (= "true" cli-auth-ext)
                "The master cert should have the auth extension for the CA CLI.")))

        (testing "is also saved in the CA's $signeddir"
          (let [signedpath (ca/path-to-cert (:signeddir ca-settings) "master")]
            (is (fs/exists? signedpath))
            (is (= hostcert (utils/pem->cert signedpath)))))))

    (testing "hostprivkey"
      (let [key (-> settings :hostprivkey utils/pem->private-key)]
        (is (utils/private-key? key))
        (is (= 512 (utils/keylength key)))))

    (testing "hostpubkey"
      (let [key (-> settings :hostpubkey utils/pem->public-key)]
        (is (utils/public-key? key))
        (is (= 512 (utils/keylength key)))))

    (testing "Does not replace files if they all exist"
      (let [files (-> (ca/settings->ssldir-paths settings)
                      (dissoc :certdir :requestdir :privatekeydir)
                      (vals))
            file-content-fn (fn [files-to-read]
                              (reduce
                               #(assoc %1 %2 (slurp %2))
                               {}
                               files-to-read))
            file-content-before-reinit (file-content-fn files)
            _ (ca/initialize-master-ssl! settings "master" ca-settings)
            file-content-after-reinit (file-content-fn files)]
        (is (= file-content-before-reinit file-content-after-reinit)
            "File content unexpectedly changed after initialization called")))

    (testing "Throws an exception if the cert is present but private key is missing"
      (let [private-key-path (:hostprivkey settings)
            private-key-backup (fs/copy private-key-path (ks/temp-file))]
        (fs/delete private-key-path)
        (is (thrown-with-msg?
             IllegalStateException
             (re-pattern
              (str "Found master cert '" (:hostcert settings)
                   "' but master private key '" (:hostprivkey settings)
                   "' is missing"))
             (ca/initialize-master-ssl! settings "master" ca-settings)))
        (fs/copy private-key-backup private-key-path)))

    (testing "Throws an exception if the private key is present but cert and public key are missing"
      (let [public-key-path (:hostpubkey settings)
            public-key-backup (fs/copy public-key-path (ks/temp-file))
            cert-path (:hostcert settings)
            cert-backup (fs/copy cert-path (ks/temp-file))]
        (fs/delete public-key-path)
        (fs/delete cert-path)
        (is (thrown-with-msg?
             IllegalStateException
             (re-pattern
              (str "Found master private key '" (:hostprivkey settings)
                   "' but master public key '" (:hostpubkey settings)
                   "' is missing"))
             (ca/initialize-master-ssl! settings "master" ca-settings)))
        (fs/copy public-key-backup public-key-path)
        (fs/copy cert-backup cert-path)))

    (testing "Throws an exception if the public key is present but cert and private key are missing"
      (let [private-key-path (:hostprivkey settings)
            private-key-backup (fs/copy private-key-path (ks/temp-file))
            cert-path (:hostcert settings)
            cert-backup (fs/copy cert-path (ks/temp-file))]
        (fs/delete private-key-path)
        (fs/delete cert-path)
        (is (thrown-with-msg?
             IllegalStateException
             (re-pattern
              (str "Found master public key '" (:hostpubkey settings)
                   "' but master private key '" (:hostprivkey settings)
                   "' is missing"))
             (ca/initialize-master-ssl! settings "master" ca-settings)))
        (fs/copy private-key-backup private-key-path)
        (fs/copy cert-backup cert-path)))

    (testing "hostcert regenerated if keys already present at initialization time"
      (let [hostcert-path (:hostcert settings)
            hostcert-backup (fs/copy hostcert-path (ks/temp-file))
            public-key-before-init (slurp (:hostpubkey settings))
            _ (fs/delete hostcert-path)
            _ (ca/initialize-master-ssl! settings "master" ca-settings)
            hostcert-after-init (utils/pem->cert hostcert-path)
            public-key-from-new-cert (StringWriter.)]
        (-> hostcert-after-init
            (.getPublicKey)
            (utils/obj->pem! public-key-from-new-cert))
        (testutils/assert-subject hostcert-after-init "CN=master")
        (testutils/assert-issuer hostcert-after-init "CN=Puppet CA: localhost")
        (is (= public-key-before-init (.toString public-key-from-new-cert))
            "regenerated public key embedded in regenerated hostcert")
        (fs/copy hostcert-backup hostcert-path)))))

(deftest initialize-master-ssl!-test-with-keylength-settings
  (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
        settings (-> (testutils/master-settings tmp-confdir)
                     (assoc :keylength 768))
        ca-settings (assoc (testutils/ca-settings (str tmp-confdir "/ca")) :keylength 768)]

    (ca/retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))
    (ca/initialize-master-ssl! settings "master" ca-settings)

    (testing "hostprivkey should have correct keylength"
      (let [key (-> settings :hostprivkey utils/pem->private-key)]
        (is (utils/private-key? key))
        (is (= 768 (utils/keylength key)))))

    (testing "hostpubkey should have correct keylength"
      (let [key (-> settings :hostpubkey utils/pem->public-key)]
        (is (utils/public-key? key))
        (is (= 768 (utils/keylength key)))))))

(when-not (SSLUtils/isFIPS)
  (deftest initialize-master-ssl!-test-with-incorrect-keylength
    (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
          settings (testutils/master-settings tmp-confdir)
          ca-settings (testutils/ca-settings (str tmp-confdir "/ca"))]

      (ca/retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))

      (testing "should throw an error message with too short keylength"
        (is (thrown?
             IllegalArgumentException
             (ca/initialize-master-ssl! (assoc settings :keylength 128) "master" ca-settings))))

      (testing "should throw an error message with too large keylength"
        (is (thrown?
             IllegalArgumentException
             (ca/initialize-master-ssl! (assoc settings :keylength 32768) "master" ca-settings)))))))

(deftest parse-serial-number-test
  (is (= (ca/parse-serial-number "0001") 1))
  (is (= (ca/parse-serial-number "0010") 16))
  (is (= (ca/parse-serial-number "002A") 42)))

(deftest format-serial-number-test
  (is (= (ca/format-serial-number 1) "0001"))
  (is (= (ca/format-serial-number 16) "0010"))
  (is (= (ca/format-serial-number 42) "002A")))

(deftest next-serial-number!-test
  (let [serial-file (str (ks/temp-file))
        ca-settings (assoc (testutils/ca-settings (ks/temp-dir))
                      :serial serial-file)]
    (testing "Serial file is initialized to 1"
      (ca/initialize-serial-file! ca-settings)
      (is (= (ca/next-serial-number! ca-settings) 1)))

    (testing "The serial number file should contain the next serial number"
      (is (= "0002" (slurp serial-file))))

    (testing "subsequent calls produce increasing serial numbers"
      (is (= (ca/next-serial-number! ca-settings) 2))
      (is (= "0003" (slurp serial-file)))

      (is (= (ca/next-serial-number! ca-settings) 3))
      (is (= "0004" (slurp serial-file))))))

;; If the locking is deleted from `next-serial-number!`, this test will hang,
;; which is not as nice as simply failing ...
;; This seems to happen due to a deadlock caused by concurrently reading and
;; writing to the same file (via `slurp` and `spit`)
(deftest next-serial-number-threadsafety
  (testing "next-serial-number! is thread-safe and
            never returns a duplicate serial number"
    (let [serial-file (doto (str (ks/temp-file)) (spit "0001"))
          serials     (atom [])
          ca-settings (assoc (testutils/ca-settings (ks/temp-dir))
                        :serial serial-file)
          ;; spin off a new thread for each CPU
          promises    (for [_ (range (ks/num-cpus))]
                        (let [p (promise)]
                          (future
                            ;; get a bunch of serial numbers and keep track of them
                            (dotimes [_ 100]
                              (let [serial-number (ca/next-serial-number! ca-settings)]
                                (swap! serials conj serial-number)))
                            (deliver p 'done))
                          p))

          contains-duplicates? #(not= (count %) (count (distinct %)))]

      ; wait on all the threads to finish
      (doseq [p promises] (deref p))

      (is (false? (contains-duplicates? @serials))
          "Got a duplicate serial number")))
  (testing "next-serial-number! will timeout if lock is held"
    (let
      [serial-file (str (ks/temp-file))
       ca-settings (assoc (testutils/ca-settings (ks/temp-dir))
                     :serial serial-file
                     :serial-lock-timeout-seconds 1)
       write-lock  (.writeLock (:serial-lock ca-settings))
       completed (promise)
       caught-timeout (promise)]
      ;; acquire and hold the lock
      (.lock write-lock)
      ;; in a separate thread, try to increment the serial number
      (deref
        (future
          (try
            (ca/next-serial-number! ca-settings)
            ;; we should never get here because the lock is held, and timeout should occur
            (deliver completed true)
            (catch Exception _e
              ;; timeout occurred
              (deliver caught-timeout false)))))
      (.unlock write-lock)
      (is (not (realized? completed)))
      (is (realized? caught-timeout)))))

(defn verify-inventory-entry!
  [inventory-entry serial-number not-before not-after subject]
  (let [parts (string/split inventory-entry #" ")]
    (is (= serial-number (first parts)))
    (is (= not-before (second parts)))
    (is (= not-after (nth parts 2)))
    (is (= subject (string/join " " (subvec parts 3))))))

(deftest test-write-cert-to-inventory
  (testing "Certs can be written to an inventory file."
    (let [first-cert     (utils/pem->cert cacert)
          second-cert    (utils/pem->cert (ca/path-to-cert signeddir "localhost"))
          inventory-file (str (ks/temp-file))
          ca-settings (assoc
                        (testutils/ca-settings cadir)
                        :cert-inventory inventory-file)]
      (ca/write-cert-to-inventory! first-cert ca-settings)
      (ca/write-cert-to-inventory! second-cert ca-settings)

      (testing "The format of a cert in the inventory matches the existing
                format used by the ruby puppet code."
        (let [inventory (slurp inventory-file)
              entries   (string/split inventory #"\n")]
          (is (= (count entries) 2))

          (verify-inventory-entry!
            (first entries)
            "0x0001"
            "2020-08-19T20:23:52UTC"
            "2025-08-19T20:23:52UTC"
            "/CN=Puppet CA: localhost")

          (verify-inventory-entry!
            (second entries)
            "0x0002"
            "2020-08-19T20:26:02UTC"
            "2025-08-19T20:26:02UTC"
            "/CN=localhost"))))))

(deftest allow-duplicate-certs-test
  (let [settings (assoc (testutils/ca-sandbox! cadir) :autosign false)]
    (testing "when false"
      (let [settings (assoc settings :allow-duplicate-certs false)]
        (testing "throws exception if CSR already exists"
          (is (thrown+?
               [:kind :duplicate-cert
                :msg "test-agent already has a requested certificate; ignoring certificate request"]
               (ca/process-csr-submission! "test-agent" (csr-stream "test-agent") settings (constantly nil)))))

        (testing "throws exception if certificate already exists"
          (is (thrown+?
               [:kind :duplicate-cert
                :msg "localhost already has a signed certificate; ignoring certificate request"]
               (ca/process-csr-submission! "localhost"
                                        (io/input-stream (test-pem-file "localhost-csr.pem"))
                                        settings
                                        (constantly nil))))
          (is (thrown+?
               [:kind :duplicate-cert
                :msg "revoked-agent already has a revoked certificate; ignoring certificate request"]
               (ca/process-csr-submission! "revoked-agent"
                                        (io/input-stream (test-pem-file "revoked-agent-csr.pem"))
                                        settings
                                        (constantly nil)))))))

    (testing "when true"
      (let [settings (assoc settings :allow-duplicate-certs true)]
        (testing "new CSR overwrites existing one"
          (let [csr-path (ca/path-to-cert-request (:csrdir settings) "test-agent")
                csr      (ByteArrayInputStream. (.getBytes (slurp csr-path)))]
            (spit csr-path "should be overwritten")
            (logutils/with-test-logging
              (ca/process-csr-submission! "test-agent" csr settings (constantly nil))
              (is (logged? #"test-agent already has a requested certificate; new certificate will overwrite it" :info))
              (is (not= "should be overwritten" (slurp csr-path))
                  "Existing CSR was not overwritten"))))

        (testing "new certificate overwrites existing one"
          (let [settings  (assoc settings :autosign true)
                cert-path (ca/path-to-cert (:signeddir settings) "localhost")
                old-cert  (slurp cert-path)
                csr       (io/input-stream (test-pem-file "localhost-csr.pem"))]
            (logutils/with-test-logging
              (ca/process-csr-submission! "localhost" csr settings (constantly nil))
              (is (logged? #"localhost already has a signed certificate; new certificate will overwrite it" :info))
              (is (not= old-cert (slurp cert-path)) "Existing certificate was not overwritten"))))))))

(deftest process-csr-submission!-test
  (let [settings (testutils/ca-sandbox! cadir)]
    (testing "CSR validation policies"
      (testing "when autosign is false"
        (let [settings (assoc settings :autosign false)]
          (testing "subject policies are checked"
            (doseq [[policy subject csr-file exception]
                    [["subject-hostname mismatch" "foo" "hostwithaltnames.pem"
                      #(= {:kind :hostname-mismatch
                           :msg "Instance name \"hostwithaltnames\" does not match requested key \"foo\""}
                          (select-keys % [:kind :msg]))]
                     ["invalid characters in name" "super/bad" "bad-subject-name-1.pem"
                      #(= {:kind :invalid-subject-name
                           :msg "Subject hostname format is invalid"}
                          (select-keys % [:kind :msg]))]
                     ["wildcard in name" "foo*bar" "bad-subject-name-wildcard.pem"
                      #(= {:kind :invalid-subject-name
                           :msg "Subject contains a wildcard, which is not allowed: foo*bar"}
                          (select-keys % [:kind :msg]))]]]
              (testing policy
                (let [path (ca/path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown+? exception (ca/process-csr-submission! subject csr settings (constantly nil))))
                  (is (false? (fs/exists? path)))))))

          (testing "extension & key policies are not checked"
            (doseq [[policy subject csr-file]
                    [["subject alt name extension" "hostwithaltnames" "hostwithaltnames.pem"]
                     ["unknown extension" "meow" "meow-bad-extension.pem"]
                     ["public-private key mismatch" "luke.madstop.com" "luke.madstop.com-bad-public-key.pem"]]]
              (testing policy
                (let [path (ca/path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (ca/process-csr-submission! subject csr settings (constantly nil))
                  (is (true? (fs/exists? path)))
                  (fs/delete path)))))
          (testing "writes indicator file when asked to"
            (let [subject "meow"
                  path (ca/path-to-cert-request (:csrdir settings) subject)
                  csr  (io/input-stream (test-pem-file "meow-bad-extension.pem"))]
              (is (false? (fs/exists? path)))
              (ca/process-csr-submission! subject csr settings (constantly nil))
              (is (true? (fs/exists? path)))
              (fs/delete path)))))

      (testing "when autosign is true, all policies are checked, and"
        (let [settings (assoc settings :autosign true)]
          (testing "CSR will not be saved when"
            (doseq [[policy subject csr-file expected]
                    [["subject-hostname mismatch" "foo" "hostwithaltnames.pem"
                      #(= {:kind :hostname-mismatch
                           :msg "Instance name \"hostwithaltnames\" does not match requested key \"foo\""}
                          (select-keys % [:kind :msg]))]
                     ["subject contains invalid characters" "super/bad" "bad-subject-name-1.pem"
                      #(= {:kind :invalid-subject-name
                           :msg "Subject hostname format is invalid"}
                          (select-keys % [:kind :msg]))]
                     ["subject contains wildcard character" "foo*bar" "bad-subject-name-wildcard.pem"
                      #(=  {:kind :invalid-subject-name
                            :msg "Subject contains a wildcard, which is not allowed: foo*bar"}
                           (select-keys % [:kind :msg]))]]]
              (testing policy
                (let [path (ca/path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown+? expected (ca/process-csr-submission! subject csr settings (constantly nil))))
                  (is (false? (fs/exists? path)))))))

          (testing "CSR will be saved when"
            (doseq [[policy subject csr-file expected]
                    [["subject alt name extension exists" "hostwithaltnames" "hostwithaltnames.pem"
                      #(= {:kind :disallowed-extension
                           :msg (str "CSR 'hostwithaltnames' contains extra subject alternative names "
                                   "(DNS:altname1, DNS:altname2, DNS:altname3), which are disallowed. "
                                   "To allow subject alternative names, set allow-subject-alt-names to "
                                   "true in your ca.conf file. Then restart the puppetserver "
                                   "and try signing this certificate again.")}
                          (select-keys % [:kind :msg]))]
                     ["unknown extension exists" "meow" "meow-bad-extension.pem"
                      #(= {:kind :disallowed-extension
                           :msg "Found extensions that are not permitted: 1.9.9.9.9.9.9"}
                          (select-keys % [:kind :msg]))]
                     ["public-private key mismatch" "luke.madstop.com" "luke.madstop.com-bad-public-key.pem"
                      #(= {:kind :invalid-signature
                           :msg "CSR contains a public key that does not correspond to the signing key"}
                          (select-keys % [:kind :msg]))]]]
              (testing policy
                (let [path (ca/path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown+? expected (ca/process-csr-submission! subject csr settings (constantly nil))))
                  (is (true? (fs/exists? path)))
                  (fs/delete path)))))))

      (testing "order of validations"
        (testing "duplicates checked before subject policies"
          (let [settings (assoc settings :allow-duplicate-certs false)
                csr-with-mismatched-name (csr-stream "test-agent")]
            (is (thrown+?
                 [:kind :duplicate-cert
                  :msg "test-agent already has a requested certificate; ignoring certificate request"]
                 (ca/process-csr-submission! "not-test-agent" csr-with-mismatched-name settings (constantly nil))))))
        (testing "subject policies checked before extension & key policies"
          (let [csr-with-disallowed-alt-names (io/input-stream (test-pem-file "hostwithaltnames.pem"))]
            (is (thrown+?
                 [:kind :hostname-mismatch
                  :msg "Instance name \"hostwithaltnames\" does not match requested key \"foo\""]
                 (ca/process-csr-submission! "foo" csr-with-disallowed-alt-names settings (constantly nil))))))))))

(deftest cert-signing-extension-test
  (let [issuer-keys  (utils/generate-key-pair 512)
        issuer-pub   (utils/get-public-key issuer-keys)
        subject-keys (utils/generate-key-pair 512)
        subject-pub  (utils/get-public-key subject-keys)
        subject      "subject"
        subject-dn   (utils/cn subject)
        not-after    (-> (DateTime/now)
                         (.plus (Period/years 5))
                         (.toDate))
        not-before   (-> (DateTime/now)
                         (.plus (Period/years 5))
                         (.toDate))
        cn           (utils/cn "Root CA")
        cert         (utils/sign-certificate cn (utils/get-private-key issuer-keys)
                                             666 not-before not-after cn issuer-pub
                                             (utils/create-ca-extensions issuer-pub issuer-pub))]

    (testing "basic extensions are created for an agent"
      (let [csr  (utils/generate-certificate-request subject-keys subject-dn)
            exts (ca/create-agent-extensions csr cert)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    ca/netscape-comment-value}
                           {:oid       "2.5.29.17"
                            :critical false
                            :value    {:dns-name [subject]}}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    nil
                                       :cert          cert
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ca/ssl-server-cert ca/ssl-client-cert]}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:digital-signature :key-encipherment}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "basic extensions are created for an agent csr with dns-alt-names specified, aka subject alternative names, aka san"
      (let [alt-names-list [subject "altname1"]
            csr  (utils/generate-certificate-request
                  subject-keys
                  subject-dn
                  [(utils/subject-dns-alt-names alt-names-list false)])
            exts (ca/create-agent-extensions csr cert)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    ca/netscape-comment-value}
                           {:oid       "2.5.29.17"
                            :critical false
                            :value    {:dns-name alt-names-list}}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    nil
                                       :cert          cert
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ca/ssl-server-cert ca/ssl-client-cert]}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:digital-signature :key-encipherment}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "basic extensions are created for an agent csr with no CN in SAN"
      (let [alt-names-list ["altname1" "altname2"]
            csr  (utils/generate-certificate-request
                  subject-keys
                  subject-dn
                  [(utils/subject-dns-alt-names alt-names-list false)])
            exts (ca/create-agent-extensions csr cert)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    ca/netscape-comment-value}
                           {:oid       "2.5.29.17"
                            :critical false
                            :value    {:dns-name (conj alt-names-list subject)}}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    nil
                                       :cert          cert
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ca/ssl-server-cert ca/ssl-client-cert]}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:digital-signature :key-encipherment}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "basic extensions are created for a master"
      (let [settings      (assoc (testutils/master-settings confdir)
                            :csr-attributes "doesntexist")
            exts          (ca/create-master-extensions subject
                                                       subject-pub
                                                       cert
                                                       settings)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    ca/netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    nil
                                       :cert          cert
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ca/ssl-server-cert ca/ssl-client-cert]}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:digital-signature :key-encipherment}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}
                           {:oid      "1.3.6.1.4.1.34380.1.3.39"
                            :critical false
                            :value    "true"}
                           {:oid      utils/subject-alt-name-oid
                            :critical false
                            :value    {:dns-name ["puppet" "subject"]}}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "additional extensions are created for a master"
      (let [dns-alt-names "DNS:onefish,twofish,DNS:threefish,fourfish"
            settings      (-> (testutils/master-settings confdir)
                              (assoc :dns-alt-names dns-alt-names)
                              (assoc :csr-attributes (csr-attributes-file "csr_attributes.yaml")))
            exts          (ca/create-master-extensions subject
                                                       subject-pub
                                                       cert
                                                       settings)
            exts-expected (concat attribute-file-extensions
                                  [{:oid      "2.16.840.1.113730.1.13"
                                    :critical false
                                    :value    ca/netscape-comment-value}
                                   {:oid      "2.5.29.35"
                                    :critical false
                                    :value    {:issuer-dn     nil
                                               :public-key    nil
                                               :cert          cert
                                               :serial-number nil}}
                                   {:oid      "2.5.29.19"
                                    :critical true
                                    :value    {:is-ca false}}
                                   {:oid      "2.5.29.37"
                                    :critical true
                                    :value    [ca/ssl-server-cert ca/ssl-client-cert]}
                                   {:oid      "2.5.29.15"
                                    :critical true
                                    :value    #{:digital-signature :key-encipherment}}
                                   {:oid      "2.5.29.14"
                                    :critical false
                                    :value    subject-pub}
                                   {:oid      "1.3.6.1.4.1.34380.1.3.39"
                                    :critical false
                                    :value    "true"}
                                   {:oid      "2.5.29.17"
                                    :critical false
                                    :value    {:dns-name ["onefish"
                                                          "twofish"
                                                          "threefish"
                                                          "fourfish"
                                                          "subject"]
                                               :ip        []}}])]
        (is (= (set exts) (set exts-expected)))))

    (testing "correct subject alt name extensions are created for a master"
      (let [dns-alt-names "onefish,twofish,DNS:threefish,IP:192.168.69.90,fivefish,IP:192.168.69.91"
            exts-expected {:oid      "2.5.29.17"
                            :critical false
                            :value    {:dns-name ["onefish"
                                                  "twofish"
                                                  "threefish"
                                                  "fivefish"
                                                  "subject"]
                                       :ip       ["192.168.69.90"
                                                  "192.168.69.91"]}}]
        (is (= (ca/create-subject-alt-names-ext "subject" dns-alt-names) exts-expected))))

    (testing "A non-puppet OID read from a CSR attributes file is rejected"
      (let [config (assoc (testutils/master-settings confdir)
                          :csr-attributes
                          (csr-attributes-file "insecure_csr_attributes.yaml"))]
        (is (thrown+?
              [:kind :disallowed-extension
               :msg "Found extensions that are not permitted: 1.2.3.4"]
              (ca/create-master-extensions subject subject-pub cert config)))))

    (testing "invalid DNS alt names are rejected"
      (let [dns-alt-names "*.wildcard"]
        (is (thrown+?
              [:kind :invalid-alt-name
               :msg "Cert subjectAltName contains a wildcard, which is not allowed: *.wildcard"]
              (ca/create-master-extensions subject subject-pub cert
                                           (assoc (testutils/master-settings confdir)
                                                  :dns-alt-names dns-alt-names))))))

    (testing "basic extensions are created for a CA"
      (let [exts          (ca/create-ca-extensions subject-pub
                                                   subject-pub)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    ca/netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    subject-pub
                                       :cert          nil
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca true}}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:crl-sign :key-cert-sign}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "trusted fact extensions are properly unfiltered"
      (let [csr-exts [(utils/puppet-node-image-name "imagename" false)
                      (utils/puppet-node-preshared-key "key" false)
                      (utils/puppet-node-instance-id "instance" false)
                      (utils/puppet-node-uid "UUUU-IIIII-DDD" false)]
            csr      (utils/generate-certificate-request
                       subject-keys subject-dn csr-exts)
            exts (ca/create-agent-extensions csr cert)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    ca/netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    nil
                                       :cert          cert
                                       :serial-number nil}}
                           {:oid       "2.5.29.17"
                            :critical false
                            :value    {:dns-name ["subject"]}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ca/ssl-server-cert ca/ssl-client-cert]}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:digital-signature :key-encipherment}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}
                           {:oid      "1.3.6.1.4.1.34380.1.1.1"
                            :critical false
                            :value    "UUUU-IIIII-DDD"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.2"
                            :critical false
                            :value    "instance"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.3"
                            :critical false
                            :value    "imagename"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.4"
                            :critical false
                            :value    "key"}]]
        (is (= (set exts) (set exts-expected))
            "The puppet trusted facts extensions were not added by create-agent-extensions")))))

(deftest netscape-comment-value-test
  (testing "Netscape comment constant has expected value"
    (is (= "Puppet Server Internal Certificate" ca/netscape-comment-value))))

(deftest ensure-alt-names-allowed-test
  (let [subject-keys (utils/generate-key-pair 512)
        subject "new-cert"
        subject-dn (utils/cn subject)]
    (logutils/with-test-logging
     (testing "when allow-subject-alt-names is false"
       (testing "rejects alt names that don't match the subject"
         (let [alt-name-ext {:oid utils/subject-alt-name-oid
                             :value {:dns-name ["bad-name"]}
                             :critical false}
               csr (utils/generate-certificate-request subject-keys subject-dn [alt-name-ext])]
           (is (thrown+-with-msg?
                [:kind :disallowed-extension]
                #".*new-cert.*subject alternative names.*bad-name.*"
                (ca/ensure-subject-alt-names-allowed! csr false))))
         (let [alt-name-ext {:oid utils/subject-alt-name-oid
                             :value {:dns-name ["bad-name" subject]}
                             :critical false}
               csr (utils/generate-certificate-request subject-keys subject-dn [alt-name-ext])]
           (is (thrown+-with-msg?
                [:kind :disallowed-extension]
                #".*new-cert.*subject alternative names.*bad-name.*"
                (ca/ensure-subject-alt-names-allowed! csr false)))))
       (testing "allows a single alt name matching the subject"
         (let [alt-name-ext {:oid utils/subject-alt-name-oid
                             :value {:dns-name [subject]}
                             :critical false}
               csr (utils/generate-certificate-request subject-keys subject-dn [alt-name-ext])]
           (is (nil? (ca/ensure-subject-alt-names-allowed! csr false)))
           (is (logutils/logged? #"Allowing subject alt name" :debug))))))
    (testing "when allow-subject-alt-names is true"
      (testing "allows all alt names"
        (let [alt-name-ext {:oid utils/subject-alt-name-oid
                            :value {:dns-name [subject "another-name"]}
                            :critical false}
              csr (utils/generate-certificate-request subject-keys subject-dn [alt-name-ext])]
          (is (nil? (ca/ensure-subject-alt-names-allowed! csr true))))))))

(deftest ensure-no-authorization-extensions!-test
  (testing "when checking a csr for authorization extensions"
    (let [subject-keys (utils/generate-key-pair 512)
          subject      "borges"
          subject-dn   (utils/cn subject)

          pp-auth-ext {:oid (:pp_authorization ca/puppet-short-names)
                       :value "true"
                       :critical false}
          pp-auth-role {:oid (:pp_auth_role ca/puppet-short-names)
                        :value "com"
                        :critical false}
          auth-csr (utils/generate-certificate-request subject-keys subject-dn [pp-auth-ext])
          auth-role-csr (utils/generate-certificate-request subject-keys subject-dn [pp-auth-role])]

      (testing "pp_authorization is caught"
        (is (thrown+-with-msg?
             [:kind :disallowed-extension]
             #".*borges.*contains an authorization extension.*"
             (ca/ensure-no-authorization-extensions! auth-csr false))))
      (testing "pp_auth_role is caught"
        (is (thrown+-with-msg?
             [:kind :disallowed-extension]
             #".*borges.*contains an authorization extension..*"
             (ca/ensure-no-authorization-extensions! auth-role-csr false)))))))

(deftest validate-subject!-test
  (testing "an exception is thrown when the hostnames don't match"
    (is (thrown+?
         [:kind :hostname-mismatch
          :msg "Instance name \"test-agent\" does not match requested key \"not-test-agent\""]
         (ca/validate-subject!
          "not-test-agent" "test-agent"))))

  (testing "an exception is thrown if the subject name contains a capital letter"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Certificate names must be lower case."]
         (ca/validate-subject! "Host-With-Capital-Letters"
                               "Host-With-Capital-Letters"))))

  (testing "an exception is thrown when the hostnames ends in hyphen"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Subject hostname format is invalid"]
         (ca/validate-subject!
          "rootca-.example.org" "rootca-.example.org"))))

  (testing "an exception is thrown when the hostnames starts with hyphen"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Subject hostname format is invalid"]
         (ca/validate-subject!
          "-rootca.example.org" "-rootca.example.org"))))

  (testing "an exception is thrown when the hostnames contains a space"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Subject hostname format is invalid"]
         (ca/validate-subject!
          "root ca.example.org" "root ca.example.org"))))

  (testing "an exception is thrown when the hostnames contain an ampersand"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Subject hostname format is invalid"]
         (ca/validate-subject!
          "root&ca.example.org" "root&ca.example.org"))))

  (testing "an exception is thrown when the hostname is empty"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Subject hostname format is invalid"]
         (ca/validate-subject!
          "" ""))))

  (testing "an exception is thrown when the hostnames contain multiple dots in a row"
    (is (thrown+?
         [:kind :invalid-subject-name
          :msg "Subject hostname format is invalid"]
         (ca/validate-subject!
          "rootca..example.org" "rootca..example.org"))))

  (testing "subjects that end end in dot are valid"
    (is (nil?
          (ca/validate-subject!
           "rootca." "rootca."))))

  (testing "subjects that end in an underscore are valid"
    (is (nil?
          (ca/validate-subject!
            "rootca_" "rootca_"))))

  (testing "subjects that start in an underscore are valid"
    (is (nil?
          (ca/validate-subject!
            "_x-puppet._tcp.example.com" "_x-puppet._tcp.example.com"))))

  (testing "single letter segments are valid"
    (is (nil?
          (ca/validate-subject!
            "a.example.com" "a.example.com")))
    (is (nil?
          (ca/validate-subject!
            "_.example.com" "_.example.com")))
    (is (nil?
          (ca/validate-subject!
            "foo.a.example.com" "foo.a.example.com"))))

  (testing "Single word hostnames are allowed"
    (is (nil?
         (ca/validate-subject!
          "rootca" "rootca"))))

  (testing "Domain names are allowed"
    (is (nil?
         (ca/validate-subject!
          "puppet.com" "puppet.com"))))

  (testing "Subdomains are allowed"
    (is (nil?
         (ca/validate-subject!
          "ca.puppet.com" "ca.puppet.com"))))

  (testing "Hostnames containing underscores are allowed"
    (is (nil?
         (ca/validate-subject!
          "root_ca" "root_ca"))))

  (testing "Hostnames containing dashes are allowed"
    (is (nil?
         (ca/validate-subject!
          "root-ca" "root-ca"))))

  (testing "Hostnames containing numbers are allowed"
    (is (nil?
         (ca/validate-subject!
          "root123" "root123"))))

  (testing "Domains containing numbers are allowed"
    (is (nil?
         (ca/validate-subject!
          "root123.com" "root123.com")))))

(deftest validate-subject-alt-names!-test
  (testing "Both DNS and IP alt names are allowed"
    (is (nil?
          (ca/validate-subject-alt-names! {:oid "2.5.29.17"
                                           :critical false
                                           :value {:ip ["12.34.5.6"] :dns-name ["ahostname"]}}))))

  (testing "Non-DNS and IP names are not allowed"
    (is (thrown+?
          [:kind :invalid-alt-name
           :msg "Only DNS and IP names are allowed in the Subject Alternative Names extension"]
          (ca/validate-subject-alt-names! {:oid "2.5.29.17"
                                           :critical false
                                           :value {:uri ["12.34.5.6"]}}))))

  (testing "No DNS wildcards are allowed"
    (is (thrown+?
          [:kind :invalid-alt-name
           :msg "Cert subjectAltName contains a wildcard, which is not allowed: foo*bar"]
          (ca/validate-subject-alt-names! {:oid "2.5.29.17"
                                           :critical false
                                           :value {:dns-name ["ahostname" "foo*bar"]}})))))

(deftest default-master-dns-alt-names
  (testing "Master certificate has default DNS alt names if none are specified"
    (let [settings     (assoc (testutils/master-settings confdir)
                              :dns-alt-names "")
          pubkey       (-> (utils/generate-key-pair 512)
                           (utils/get-public-key))
          ca-key-pair  (utils/generate-key-pair 512)
          ca-priv-key  (utils/get-private-key ca-key-pair)
          ca-pub-key   (utils/get-public-key ca-key-pair)
          not-after    (-> (DateTime/now)
                           (.plus (Period/years 5))
                           (.toDate))
          not-before   (-> (DateTime/now)
                           (.plus (Period/years 5))
                           (.toDate))
          cn           (utils/cn "Root CA")
          cert         (utils/sign-certificate cn ca-priv-key
                                               666 not-before not-after cn ca-pub-key
                                               (utils/create-ca-extensions ca-pub-key ca-pub-key))
          alt-names (-> (ca/create-master-extensions "master" pubkey cert settings)
                        (utils/get-extension-value utils/subject-alt-name-oid)
                        (:dns-name))]
      (is (= #{"puppet" "master"} (set alt-names))))))

(deftest file-permissions
  (testing "A newly created file contains the properly set permissions"
    (doseq [u all-perms
            g all-perms
            o all-perms]
      (let [tmp-file (fs/temp-name "ca-file-perms-test")
            perms (str u g o)]
        (ca/create-file-with-perms tmp-file perms)
        (is (= perms (ks-file/get-perms tmp-file)))
        (fs/delete tmp-file))))

  (testing "Changing the perms of an already created file"
    (let [perms-list (for [u all-perms
                           g all-perms
                           o all-perms]
                       (str u g o))]
      (loop [perms perms-list]
        (when-not (empty? perms)
          (let [tmp-file (fs/temp-name "ca-file-perms-test")
                [init-perm change-perm] (take 2 perms)]
            (ca/create-file-with-perms tmp-file init-perm)
            (ks-file/set-perms tmp-file change-perm)
            (is (= change-perm (ks-file/get-perms tmp-file)))
            (fs/delete tmp-file)
            (recur (nthnext perms 2))))))))

(deftest create-csr-attrs-exts-test
  (testing "when parsing a csr_attributes file using short names"
    (testing "and the file exists"
      (testing "and it has non-whitelisted OIDs we properly translate the short names."
        (let [extensions (ca/create-csr-attrs-exts (csr-attributes-file "csr_attributes_with_auth.yaml"))
              expected (concat attribute-file-extensions
                               [{:oid "1.3.6.1.4.1.34380.1.3.1" ;; :pp_authorization
                                 :critical false
                                 :value "true"}
                                {:oid "1.3.6.1.4.1.34380.1.3.13" ;; :pp_auth_role
                                 :critical false
                                 :value "com"}])]
          (is (= (set extensions) (set expected)))))
      (testing "and it has whitelisted OIDs we properly translate the short names."
        (let [extensions (ca/create-csr-attrs-exts (csr-attributes-file "csr_attributes.yaml"))]
          (is (= (set extensions) (set attribute-file-extensions))))))
    (testing "and the file doesn't exist"
      (testing "the result is nil"
        (is (nil? (ca/create-csr-attrs-exts "does/not/exist.yaml")))))))

(deftest ca-expiration-dates-test
  (testing "returns a map of names to dates"
    (let [settings (testutils/ca-sandbox! bundle-cadir)
          expiration-map (ca/ca-expiration-dates (:cacert settings))]
      (is (= "2036-09-06T05:58:33UTC" (get expiration-map "rootca.example.org")))
      (is (= "2036-09-06T06:09:14UTC" (get expiration-map "intermediateca.example.org"))))))

(deftest crl-expiration-dates-test
  (testing "returns a map of names to dates"
    (let [settings (testutils/ca-sandbox! bundle-cadir)
          expiration-map (ca/crl-expiration-dates (:cacrl settings))]
      (is (= "2016-10-11T06:42:52UTC" (get expiration-map "rootca.example.org")))
      (is (= "2016-10-11T06:40:47UTC" (get expiration-map "intermediateca.example.org"))))))

(deftest get-cert-or-csr-statuses-test
  (let [lock (new ReentrantReadWriteLock)
        descriptor "test-crl"
        timeout 1
        crl (-> (ca/get-certificate-revocation-list cacrl lock descriptor timeout)
                StringReader.
                utils/pem->crl)]
    (testing "returns a collection of 'requested' statuses when queried for CSR"
      (let [request-statuses (ca/get-cert-or-csr-statuses csrdir crl false)
            result-states (map :state request-statuses)]
        (is (every? #(= "requested" %) result-states))))

    (testing "returns a collection of 'signed' or 'revoked' statuses when queried for cert"
      (let [cert-statuses (ca/get-cert-or-csr-statuses signeddir crl true)
            result-states (map :state cert-statuses)]
        (is (every? #(or (= "signed" %) (= "revoked" %)) result-states))))

    (testing "errors when given wrong directory path for querying CSR"
      (is (thrown?
           java.lang.ClassCastException
           (ca/get-cert-or-csr-statuses signeddir crl false))))))

(defn create-ca-cert
  [name serial]
  (let [keypair (utils/generate-key-pair)
        public-key (utils/get-public-key keypair)
        private-key (utils/get-private-key keypair)
        x500-name (utils/cn name)
        validity (ca/cert-validity-dates 3600)
        ca-exts (ca/create-ca-extensions public-key public-key)]
    {:public-key public-key
     :private-key private-key
     :x500-name x500-name
     :certname name
     :cert (utils/sign-certificate
             x500-name
             private-key
             serial
             (:not-before validity)
             (:not-after validity)
             x500-name
             public-key
             ca-exts)}))

(deftest cert-authority-id-match-ca-subject-id?-test
  (let [ca-cert-1 (create-ca-cert "ca1" 1)
        ca-cert-2 (create-ca-cert "ca2" 2)
        ca-cert-3 (create-ca-cert "ca3" 3)
        cert-1 (simple/gen-cert "foo" ca-cert-1 4 {:extensions [(utils/authority-key-identifier (:cert ca-cert-1))]})
        cert-2 (simple/gen-cert "foo" ca-cert-2 5 {:extensions [(utils/authority-key-identifier (:cert ca-cert-2))]})
        cert-3 (simple/gen-cert "foo" ca-cert-3 5 {:extensions [(utils/authority-key-identifier (:cert ca-cert-3))]})]
    (testing "Certificates that match CA report as matching"
      (is (true? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-1) (:cert ca-cert-1))))
      (is (true? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-2) (:cert ca-cert-2))))
      (is (true? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-3) (:cert ca-cert-3)))))
    (testing "Certificates that don't match CA report as not matching"
      (is (false? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-1) (:cert ca-cert-2))))
      (is (false? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-1) (:cert ca-cert-3))))
      (is (false? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-2) (:cert ca-cert-1))))
      (is (false? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-2) (:cert ca-cert-3))))
      (is (false? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-3) (:cert ca-cert-1))))
      (is (false? (ca/cert-authority-id-match-ca-subject-id? (:cert cert-3) (:cert ca-cert-2)))))))

(deftest duration-string-conversion-test
  (testing "a valid duration string coverts to expected seconds"
    (let [duration-str-1 "1y 1d 1h 1m 1s"
          duration-str-2 "800y 0d 0h 0m 0s"
          duration-str-3 "22d1s"
          duration-str-4 "0s"]
      (is (= 31626061 (ca/duration-str->sec duration-str-1)))
      (is (= 25228800000 (ca/duration-str->sec duration-str-2)))
      (is (= 1900801 (ca/duration-str->sec duration-str-3)))
      (is (= 0 (ca/duration-str->sec duration-str-4)))))
  (testing "an invalid duration string returns nil"
    (let [duration-str-1 "not a duration string 283q 3z 3x 03o"
          duration-str-2 "not a duration string 20d 20s"
          duration-str-3 "1    y       1__d    30 m    s22   m39  thirtym"
          duration-str-4 "0y 0d 0h 0m 0s 0x 0y 0z"
          duration-str-5 33
          duration-str-6 nil]
      (is (= nil (ca/duration-str->sec duration-str-1)))
      (is (= nil (ca/duration-str->sec duration-str-2)))
      (is (= nil (ca/duration-str->sec duration-str-3)))
      (is (= nil (ca/duration-str->sec duration-str-4)))
      (is (= nil (ca/duration-str->sec duration-str-5)))
      (is (= nil (ca/duration-str->sec duration-str-6))))))
(deftest renew-certificate!-test
  (testing "creates a new signed cert"
    (let [settings (testutils/ca-sandbox! cadir)
          ;; auto-renewal-cert-ttl is expected to be an int
          ;; unit tests skip some of the conversion flow so
          ;; transform the duration here
          converted-auto-renewal-cert-ttl (ca/duration-str->sec (:auto-renewal-cert-ttl settings))
          updated-settings (assoc settings :auto-renewal-cert-ttl converted-auto-renewal-cert-ttl)
          ca-cert (create-ca-cert "ca1" 1)
          keypair (utils/generate-key-pair)
          subject (utils/cn "foo")
          csr  (utils/generate-certificate-request keypair subject)
          validity (ca/cert-validity-dates 3600)
          signed-cert (utils/sign-certificate
                        (utils/get-subject-from-x509-certificate (:cert ca-cert))
                        (:private-key ca-cert)
                        (ca/next-serial-number! settings)
                        (:not-before validity)
                        (:not-after validity)
                        subject
                        (utils/get-public-key csr)
                        (ca/create-agent-extensions csr (:cert ca-cert)))
          expected-cert-path (ca/path-to-cert (:signeddir settings) "foo")]
      (testing "simulate the cert being written"
        (ca/write-cert signed-cert expected-cert-path)
        (is (fs/exists? expected-cert-path)))
      (Thread/sleep 1000) ;; ensure there is some time elapsed between the two
      (let [renewed-cert (ca/renew-certificate! signed-cert updated-settings (constantly nil))]
        (is (some? renewed-cert))
        (testing "serial number has increased"
          (is (< (.getSerialNumber signed-cert) (.getSerialNumber renewed-cert)))
          (is (= 6 (.getSerialNumber renewed-cert))))
        (testing "not before time stamps have changed"
          (is (= -1 (.compareTo (.getNotBefore signed-cert) (.getNotBefore renewed-cert)))))
        (testing "new not-after is later than before"
          (is (= -1 (.compareTo (.getNotAfter signed-cert) (.getNotAfter renewed-cert)))))
        (testing "new not-after should be 89 days (and some faction) away"
          (let [diff (- (.getTime (.getNotAfter renewed-cert)) (.getTime (Date.)))
                days (.convert TimeUnit/DAYS diff TimeUnit/MILLISECONDS)]
            (is (= 89 days))))
        (testing "certificate should have been removed"
          (is (not (fs/exists? expected-cert-path))))
        (testing "extensions are preserved"
          (let [extensions-before (utils/get-extensions signed-cert)
                extensions-after (utils/get-extensions signed-cert)]
            ;; ordering may be different so use an unordered comparison
            (is (= (set extensions-before)
                   (set extensions-after)))))
        (testing "the new entry is written to the inventory file"
          (let [entries (string/split (slurp (:cert-inventory settings)) #"\n")
                last-entry-fields (string/split (last entries) #" ")]
            ;; since the content of the inventory is well established (because of the sandbox), we can
            ;; just assert that the last entry is there, and makes sense
            ;; there are four fields, serial number, not before, not after, and subject
            ;; for ease of testing, just test the first and last
            (is (= "0x0006" (first last-entry-fields)))
            (is (= "/CN=foo" (last last-entry-fields)))))))))
(deftest supports-auto-renewal?-test
  (let [keypair (utils/generate-key-pair)
        subject (utils/cn "foo")]
    (testing "should not support auto-renewal"
      (is (false? (ca/supports-auto-renewal? (utils/generate-certificate-request keypair subject [] []))))
      (is (false? (ca/supports-auto-renewal? (utils/generate-certificate-request keypair subject [] [{:oid "1.3.6.1.4.1.34380.1.3.2" :value false}]))))
      (is (false? (ca/supports-auto-renewal? (utils/generate-certificate-request keypair subject [] [{:oid "1.3.6.1.4.1.34380.1.3.2" :value "false"}])))))
    (testing "should support auto-renewal"
      (is (true? (ca/supports-auto-renewal? (utils/generate-certificate-request keypair subject [] [{:oid "1.3.6.1.4.1.34380.1.3.2" :value true}]))))
      (is (true? (ca/supports-auto-renewal? (utils/generate-certificate-request keypair subject [] [{:oid "1.3.6.1.4.1.34380.1.3.2" :value "true"}])))))))

(deftest get-csr-attributes-test
  (testing "extract attribute from CSR"
    (let [keypair (utils/generate-key-pair)
          subject (utils/cn "foo")
          csr  (utils/generate-certificate-request keypair subject [] [{:oid "1.3.6.1.4.1.34380.1.3.2" :value true}])]
      (is (= [{:oid "1.3.6.1.4.1.34380.1.3.2", :values ["true"]}] (ca/get-csr-attributes csr))))))
