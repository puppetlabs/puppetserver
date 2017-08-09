(ns puppetlabs.puppetserver.certificate-authority-test
  (:import (java.io StringReader
                    StringWriter
                    ByteArrayInputStream
                    ByteArrayOutputStream)
           (java.security InvalidParameterException))
  (:require [puppetlabs.puppetserver.certificate-authority :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.ssl-utils.core :as utils]
            [puppetlabs.services.ca.ca-testutils :as testutils]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.test :refer :all]
            [schema.test :as schema-test]
            [clojure.test :refer :all]
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
(def cadir (str ssldir "/ca"))
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
  (io/input-stream (path-to-cert-request csrdir subject)))

(defn write-to-stream [o]
  (let [s (ByteArrayOutputStream.)]
    (utils/obj->pem! o s)
    (-> s .toByteArray ByteArrayInputStream.)))

(defn assert-autosign [whitelist subject]
  (testing subject
    (is (true? (autosign-csr? whitelist subject empty-stream [])))))

(defn assert-no-autosign [whitelist subject]
  (testing subject
    (is (false? (autosign-csr? whitelist subject empty-stream [])))))

(defn contains-ext?
  "Does the provided extension list contain an extensions with the given OID."
  [ext-list oid]
  (> (count (filter #(= oid (:oid %)) ext-list)) 0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest validate-settings-test
  (testing "invalid ca-ttl is rejected"
    (let [settings (assoc
                     (testutils/ca-settings cadir)
                     :ca-ttl
                     (+ max-ca-ttl 1))]
      (is (thrown-with-msg? IllegalStateException #"ca_ttl must have a value below"
                            (validate-settings! settings)))))

  (testing "warns if :client-whitelist is set in c-a.c-s section"
    (let [settings (assoc-in
                     (testutils/ca-settings cadir)
                     [:access-control :certificate-status :client-whitelist]
                     ["whitelist"])]
      (logutils/with-test-logging
        (validate-settings! settings)
        (is (logutils/logged? #"Remove these settings and create" :warn)))))

  (testing "warns if :authorization-required is overridden in c-a.c-s section"
    (let [settings (assoc-in
                     (testutils/ca-settings cadir)
                     [:access-control
                      :certificate-status
                      :authorization-required]
                     false)]
      (logutils/with-test-logging
        (validate-settings! settings)
        (is (logutils/logged? #"Remove these settings and create" :warn)))))

  (testing "warns if :client-whitelist is set incorrectly"
    (let [settings (assoc-in
                     (testutils/ca-settings cadir)
                     [:access-control :certificate-status :client-whitelist]
                     [])]
      (logutils/with-test-logging
        (validate-settings! settings)
        (is (logutils/logged?
              #"remove the 'certificate-authority' configuration"
              :warn))))))

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
    (is (true? (autosign-csr? true "unused" empty-stream [])))
    (is (false? (autosign-csr? false "unused" empty-stream []))))

  (testing "whitelist"
    (testing "autosign is false when whitelist doesn't exist"
      (is (false? (autosign-csr? "Foo/conf/autosign.conf" "doubleagent"
                                 empty-stream []))))

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
          (logutils/with-log-output logs
            (assert-no-autosign whitelist invalid-line)
            (is (logutils/logs-matching
                 (re-pattern (format "Invalid pattern '%s' found in %s"
                                     invalid-line whitelist))
                 @logs))
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
        ruby-load-path ["ruby/puppet/lib" "ruby/facter/lib" "ruby/hiera/lib"]]

    (testing "stdout is added to master's log at debug level"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) ruby-load-path)
        (is (logged? #"print to stdout" :debug))))

    (testing "stderr is added to master's log at warn level"
      (logutils/with-test-logging
       (autosign-csr? executable "test-agent" (csr-fn) ruby-load-path)
       (is (logged? #"generated output to stderr: print to stderr" :warn))))

    (testing "non-zero exit-code generates a log entry at warn level"
      (logutils/with-test-logging
       (autosign-csr? executable "foo" (csr-fn) ruby-load-path)
       (is (logged? #"rejected certificate 'foo'" :warn))))

    (testing "Ruby load path is configured and contains Puppet"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) ruby-load-path)
        (is (logged? #"Ruby load path configured properly"))))

    (testing "subject is passed as argument and CSR is provided on stdin"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) ruby-load-path)
        (is (logged? #"subject: test-agent"))
        (is (logged? #"CSR for: test-agent"))))

    (testing "only exit code 0 results in autosigning"
      (logutils/with-test-logging
        (is (true? (autosign-csr? executable "test-agent" (csr-fn) ruby-load-path)))
        (is (false? (autosign-csr? executable "foo" (csr-fn) ruby-load-path)))))))

(deftest autosign-csr?-bash-exe-test
  (let [executable (autosign-exe-file "bash-autosign-executable")
        csr-fn #(csr-stream "test-agent")]

    (testing "stdout is added to master's log at debug level"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) [])
        (is (logged? #"print to stdout" :debug))))

    (testing "stderr is added to master's log at warn level"
      (logutils/with-test-logging
       (autosign-csr? executable "test-agent" (csr-fn) [])
       (is (logged? #"generated output to stderr: print to stderr" :warn))))

    (testing "non-zero exit-code generates a log entry at warn level"
      (logutils/with-test-logging
       (autosign-csr? executable "foo" (csr-fn) [])
       (is (logged? #"rejected certificate 'foo'" :warn))))

    (testing "subject is passed as argument and CSR is provided on stdin"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) [])
        (is (logged? #"subject: test-agent"))
        (is (logged? #"-----BEGIN CERTIFICATE REQUEST-----"))))

    (testing "only exit code 0 results in autosigning"
      (logutils/with-test-logging
        (is (true? (autosign-csr? executable "test-agent" (csr-fn) [])))
        (is (false? (autosign-csr? executable "foo" (csr-fn) [])))))))

(deftest save-certificate-request!-test
  (testing "requests are saved to disk"
    (let [csrdir   (:csrdir (testutils/ca-sandbox! cadir))
          csr      (utils/pem->csr (path-to-cert-request csrdir "test-agent"))
          path     (path-to-cert-request csrdir "foo")]
      (is (false? (fs/exists? path)))
      (save-certificate-request! "foo" csr csrdir)
      (is (true? (fs/exists? path)))
      (is (= (get-certificate-request csrdir "foo")
             (get-certificate-request csrdir "test-agent"))))))

(deftest autosign-certificate-request!-test
  (let [now                (time/epoch)
        two-years          (* 60 60 24 365 2)
        settings           (-> (testutils/ca-sandbox! cadir)
                               (assoc :ca-ttl two-years))
        csr                (-> (:csrdir settings)
                               (path-to-cert-request "test-agent")
                               (utils/pem->csr))
        expected-cert-path (path-to-cert (:signeddir settings) "test-agent")]
    ;; Fix the value of "now" so we can reliably test the dates
    (time/do-at now
      (autosign-certificate-request! "test-agent" csr settings))

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
                        (path-to-cert-request "test-agent")
                        (utils/pem->csr))
          cert-path (path-to-cert (:signeddir settings) "test-agent")]
      (fs/delete (:capub settings))
      (autosign-certificate-request! "test-agent" csr settings)
      (is (true? (fs/exists? cert-path)))
      (let [cert  (utils/pem->cert cert-path)
            capub (-> (:cacert settings)
                      (utils/pem->cert)
                      (.getPublicKey))]
        (is (nil? (.verify cert capub)))))))

(deftest autosign-with-bundled-ca-certs
  (testing "The CA public key file can be a bundle of certs"
    (let [settings  (testutils/ca-sandbox! bundle-cadir)
          csr       (-> (:csrdir settings)
                        (path-to-cert-request "test-agent")
                        (utils/pem->csr))
          cert-path (path-to-cert (:signeddir settings) "test-agent")]
      (autosign-certificate-request! "test-agent" csr settings)
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
                       (path-to-cert "localhost")
                       (utils/pem->cert))
          revoked? (fn [cert]
                     (-> (:cacrl settings)
                         (utils/pem->crl)
                         (utils/revoked? cert)))]
      (fs/delete (:capub settings))
      (is (false? (revoked? cert)))
      (revoke-existing-cert! settings "localhost")
      (is (true? (revoked? cert))))))

(deftest get-certificate-revocation-list-test
  (testing "`get-certificate-revocation-list` returns a valid CRL file."
    (let [crl (-> (get-certificate-revocation-list cacrl)
                  StringReader.
                  utils/pem->crl)]
      (testutils/assert-issuer crl "CN=Puppet CA: localhost"))))

(deftest initialize!-test
  (let [settings (testutils/ca-settings (ks/temp-dir))]

    (initialize! settings)

    (testing "Generated SSL file"
      (doseq [file (vals (settings->cadir-paths settings))]
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
            (is (= #{:key-cert-sign :crl-sign} key-usage))))))

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

    (testing "Does not replace files if they all exist"
      (let [files (-> (settings->cadir-paths settings)
                      (dissoc :csrdir :signeddir)
                      (vals))]
        (doseq [f files] (spit f "testable string"))
        (initialize! settings)
        (doseq [f files] (is (= "testable string" (slurp f))
                             "File was replaced"))))))

(deftest initialize!-test-with-keylength-in-settings
  (let [settings (assoc (testutils/ca-settings (ks/temp-dir)) :keylength 768)]
    (initialize! settings)
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
          (is (nil? (initialize! settings)))
          (is (true? (fs/exists? (get settings dir))))))))

  (testing "CA public key not required"
    (let [settings (testutils/ca-sandbox! cadir)]
      (fs/delete (:capub settings))
      (is (nil? (initialize! settings)))))

  (testing "Exception is thrown when required file is missing"
    (doseq [file required-ca-files]
      (testing file
        (let [settings (testutils/ca-sandbox! cadir)
              path     (get settings file)]
          (fs/delete path)
          (is (thrown-with-msg?
               IllegalStateException
               (re-pattern (str "Missing:\n" path))
               (initialize! settings)))))))

  (testing (str "The CA private key has its permissions properly reset when "
                ":manage-internal-file-permissions is true.")
    (let [settings (testutils/ca-sandbox! cadir)]
      (set-file-perms (:cakey settings) "rw-r--r--")
      (logutils/with-test-logging
        (initialize! settings)
        (is (logged? #"/ca/ca_key.pem' was found to have the wrong permissions set as 'rw-r--r--'. This has been corrected to 'rw-r-----'."))
        (is (= private-key-perms (get-file-perms (:cakey settings)))))))

  (testing (str "The CA private key's permissions are not reset if "
                ":manage-internal-file-permissions is false.")
    (let [perms "rw-r--r--"
          settings (assoc (testutils/ca-sandbox! cadir)
                     :manage-internal-file-permissions false)]
      (set-file-perms (:cakey settings) perms)
      (initialize! settings)
      (is (= perms (get-file-perms (:cakey settings)))))))

(deftest retrieve-ca-cert!-test
  (testing "CA file copied when it doesn't already exist"
    (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
          settings    (testutils/master-settings tmp-confdir)
          ca-settings (testutils/ca-settings (str tmp-confdir "/ssl/ca"))
          cacert      (:cacert ca-settings)
          localcacert (:localcacert settings)
          cacert-text (slurp cacert)]

      (testing "Copied cacert to localcacert when localcacert not present"
        (retrieve-ca-cert! cacert localcacert)
        (is (= (slurp localcacert) cacert-text)
            (str "Unexpected content for localcacert: " localcacert)))

      (testing "Doesn't copy cacert over localcacert when different localcacert present"
        (let [localcacert-contents "12345"]
          (spit (:localcacert settings) localcacert-contents)
          (retrieve-ca-cert! cacert localcacert)
          (is (= (slurp localcacert) localcacert-contents)
              (str "Unexpected content for localcacert: " localcacert))))

      (testing "Throws exception if no localcacert and no cacert to copy"
        (fs/delete localcacert)
        (let [copy (fs/copy cacert (ks/temp-file))]
          (fs/delete cacert)
          (is (thrown? IllegalStateException
                       (retrieve-ca-cert! cacert localcacert))
              "No exception thrown even though no file existed for copying")
          (fs/copy copy cacert))))))

(deftest retrieve-ca-crl!-test
  (testing "CRL file copied when it doesn't already exist"
    (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
          settings    (testutils/master-settings tmp-confdir)
          ca-settings (testutils/ca-settings (str tmp-confdir "/ssl/ca"))
          cacrl       (:cacrl ca-settings)
          hostcrl     (:hostcrl settings)
          cacrl-text (slurp cacrl)]

      (testing "Copied cacrl to hostcrl when hostcrl not present"
        (retrieve-ca-crl! cacrl hostcrl)
        (is (= (slurp hostcrl) cacrl-text)
            (str "Unexpected content for hostcrl: " hostcrl)))

      (testing "Copied cacrl to hostcrl when different hostcrl present"
        (spit (:hostcrl settings) "12345")
        (retrieve-ca-crl! cacrl hostcrl)
        (is (= (slurp hostcrl) cacrl-text)
            (str "Unexpected content for hostcrl: " hostcrl)))

      (testing (str "Doesn't throw exception or create dummy file if no "
                    "hostcrl and no cacrl to copy")
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
        ca-settings (testutils/ca-settings (str tmp-confdir "/ssl/ca"))]

    (retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))
    (retrieve-ca-crl! (:cacrl ca-settings) (:hostcrl settings))
    (initialize-master-ssl! settings "master" ca-settings)

    (testing "Generated SSL file"
      (doseq [file (vals (settings->ssldir-paths settings))]
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

        (testing "is also saved in the CA's $signeddir"
          (let [signedpath (path-to-cert (:signeddir ca-settings) "master")]
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
      (let [files (-> (settings->ssldir-paths settings)
                      (dissoc :certdir :requestdir :privatekeydir)
                      (vals))
            file-content-fn (fn [files-to-read]
                              (reduce
                               #(assoc %1 %2 (slurp %2))
                               {}
                               files-to-read))
            file-content-before-reinit (file-content-fn files)
            _ (initialize-master-ssl! settings "master" ca-settings)
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
             (initialize-master-ssl! settings "master" ca-settings)))
        (fs/copy private-key-backup private-key-path)))

    (testing (str "Throws an exception if the private key is present but cert "
                  "and public key are missing")
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
             (initialize-master-ssl! settings "master" ca-settings)))
        (fs/copy public-key-backup public-key-path)
        (fs/copy cert-backup cert-path)))

    (testing (str "Throws an exception if the public key is present but cert "
                  "and private key are missing")
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
             (initialize-master-ssl! settings "master" ca-settings)))
        (fs/copy private-key-backup private-key-path)
        (fs/copy cert-backup cert-path)))

    (testing "hostcert regenerated if keys already present at initialization time"
      (let [hostcert-path (:hostcert settings)
            hostcert-backup (fs/copy hostcert-path (ks/temp-file))
            public-key-before-init (slurp (:hostpubkey settings))
            _ (fs/delete hostcert-path)
            _ (initialize-master-ssl! settings "master" ca-settings)
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
        ca-settings (assoc (testutils/ca-settings (str tmp-confdir "/ssl/ca")) :keylength 768)]

  (retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))
  (initialize-master-ssl! settings "master" ca-settings)

  (testing "hostprivkey should have correct keylength"
    (let [key (-> settings :hostprivkey utils/pem->private-key)]
      (is (utils/private-key? key))
      (is (= 768 (utils/keylength key)))))

  (testing "hostpubkey should have correct keylength"
    (let [key (-> settings :hostpubkey utils/pem->public-key)]
      (is (utils/public-key? key))
      (is (= 768 (utils/keylength key)))))))

(deftest initialize-master-ssl!-test-with-incorrect-keylength
  (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
        settings (testutils/master-settings tmp-confdir)
        ca-settings (testutils/ca-settings (str tmp-confdir "/ssl/ca"))]

    (retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))

    (testing "should throw an error message with too short keylength"
      (is (thrown-with-msg?
        InvalidParameterException
        #".*RSA keys must be at least 512 bits long.*"
          (initialize-master-ssl! (assoc settings :keylength 128) "master" ca-settings))))

    (testing "should throw an error message with too large keylength"
      (is (thrown-with-msg?
        InvalidParameterException
        #".*RSA keys must be no longer than 16384 bits.*"
          (initialize-master-ssl! (assoc settings :keylength 32768) "master" ca-settings))))))

(deftest initialize-master-ssl!-test-with-keylength-settings
  (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
        settings (-> (testutils/master-settings tmp-confdir)
                     (assoc :keylength 768))
        ca-settings (assoc (testutils/ca-settings (str tmp-confdir "/ssl/ca")) :keylength 768)]

  (retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))
  (initialize-master-ssl! settings "master" ca-settings)

  (testing "hostprivkey should have correct keylength"
    (let [key (-> settings :hostprivkey utils/pem->private-key)]
      (is (utils/private-key? key))
      (is (= 768 (utils/keylength key)))))

  (testing "hostpubkey should have correct keylength"
    (let [key (-> settings :hostpubkey utils/pem->public-key)]
      (is (utils/public-key? key))
      (is (= 768 (utils/keylength key)))))))

(deftest initialize-master-ssl!-test-with-incorrect-keylength
  (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
        settings (testutils/master-settings tmp-confdir)
        ca-settings (testutils/ca-settings (str tmp-confdir "/ssl/ca"))]

    (retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))

    (testing "should throw an error message with too short keylength"
      (is (thrown-with-msg?
        InvalidParameterException
        #".*RSA keys must be at least 512 bits long.*"
          (initialize-master-ssl! (assoc settings :keylength 128) "master" ca-settings))))

    (testing "should throw an error message with too large keylength"
      (is (thrown-with-msg?
        InvalidParameterException
        #".*RSA keys must be no longer than 16384 bits.*"
          (initialize-master-ssl! (assoc settings :keylength 32768) "master" ca-settings))))))

(deftest parse-serial-number-test
  (is (= (parse-serial-number "0001") 1))
  (is (= (parse-serial-number "0010") 16))
  (is (= (parse-serial-number "002A") 42)))

(deftest format-serial-number-test
  (is (= (format-serial-number 1) "0001"))
  (is (= (format-serial-number 16) "0010"))
  (is (= (format-serial-number 42) "002A")))

(deftest next-serial-number!-test
  (let [serial-file (str (ks/temp-file))]
    (testing "Serial file is initialized to 1"
      (initialize-serial-file! serial-file)
      (is (= (next-serial-number! serial-file) 1)))

    (testing "The serial number file should contain the next serial number"
      (is (= "0002" (slurp serial-file))))

    (testing "subsequent calls produce increasing serial numbers"
      (is (= (next-serial-number! serial-file) 2))
      (is (= "0003" (slurp serial-file)))

      (is (= (next-serial-number! serial-file) 3))
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

          ;; spin off a new thread for each CPU
          promises    (for [_ (range (ks/num-cpus))]
                        (let [p (promise)]
                          (future
                            ;; get a bunch of serial numbers and keep track of them
                            (dotimes [_ 100]
                              (let [serial-number (next-serial-number! serial-file)]
                                (swap! serials conj serial-number)))
                            (deliver p 'done))
                          p))

          contains-duplicates? #(not= (count %) (count (distinct %)))]

      ; wait on all the threads to finish
      (doseq [p promises] (deref p))

      (is (false? (contains-duplicates? @serials))
          "Got a duplicate serial number"))))

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
          second-cert    (utils/pem->cert (path-to-cert signeddir "localhost"))
          inventory-file (str (ks/temp-file))]
      (write-cert-to-inventory! first-cert inventory-file)
      (write-cert-to-inventory! second-cert inventory-file)

      (testing "The format of a cert in the inventory matches the existing
                format used by the ruby puppet code."
        (let [inventory (slurp inventory-file)
              entries   (string/split inventory #"\n")]
          (is (= (count entries) 2))

          (verify-inventory-entry!
            (first entries)
            "0x0001"
            "2014-02-14T18:09:07UTC"
            "2019-02-14T18:09:07UTC"
            "/CN=Puppet CA: localhost")

          (verify-inventory-entry!
            (second entries)
            "0x0002"
            "2014-02-14T18:09:07UTC"
            "2019-02-14T18:09:07UTC"
            "/CN=localhost"))))))

(deftest allow-duplicate-certs-test
  (let [settings (assoc (testutils/ca-sandbox! cadir) :autosign false)]
    (testing "when false"
      (let [settings (assoc settings :allow-duplicate-certs false)]
        (testing "throws exception if CSR already exists"
          (is (thrown+?
               [:kind :duplicate-cert
                :msg "test-agent already has a requested certificate; ignoring certificate request"]
               (process-csr-submission! "test-agent" (csr-stream "test-agent") settings))))

        (testing "throws exception if certificate already exists"
          (is (thrown+?
               [:kind :duplicate-cert
                :msg "localhost already has a signed certificate; ignoring certificate request"]
               (process-csr-submission! "localhost"
                                        (io/input-stream (test-pem-file "localhost-csr.pem"))
                                        settings)))
          (is (thrown+?
               [:kind :duplicate-cert
                :msg "revoked-agent already has a revoked certificate; ignoring certificate request"]
               (process-csr-submission! "revoked-agent"
                                        (io/input-stream (test-pem-file "revoked-agent-csr.pem"))
                                        settings))))))

    (testing "when true"
      (let [settings (assoc settings :allow-duplicate-certs true)]
        (testing "new CSR overwrites existing one"
          (let [csr-path (path-to-cert-request (:csrdir settings) "test-agent")
                csr      (ByteArrayInputStream. (.getBytes (slurp csr-path)))]
            (spit csr-path "should be overwritten")
            (logutils/with-test-logging
              (process-csr-submission! "test-agent" csr settings)
              (is (logged? #"test-agent already has a requested certificate; new certificate will overwrite it" :info))
              (is (not= "should be overwritten" (slurp csr-path))
                  "Existing CSR was not overwritten"))))

        (testing "new certificate overwrites existing one"
          (let [settings  (assoc settings :autosign true)
                cert-path (path-to-cert (:signeddir settings) "localhost")
                old-cert  (slurp cert-path)
                csr       (io/input-stream (test-pem-file "localhost-csr.pem"))]
            (logutils/with-test-logging
              (process-csr-submission! "localhost" csr settings)
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
                           :msg "Subject contains unprintable or non-ASCII characters"}
                          (select-keys % [:kind :msg]))]
                     ["wildcard in name" "foo*bar" "bad-subject-name-wildcard.pem"
                      #(= {:kind :invalid-subject-name
                           :msg "Subject contains a wildcard, which is not allowed: foo*bar"}
                          (select-keys % [:kind :msg]))]]]
              (testing policy
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown+? exception (process-csr-submission! subject csr settings)))
                  (is (false? (fs/exists? path)))))))

          (testing "extension & key policies are not checked"
            (doseq [[policy subject csr-file]
                    [["subject alt name extension" "hostwithaltnames" "hostwithaltnames.pem"]
                     ["unknown extension" "meow" "meow-bad-extension.pem"]
                     ["public-private key mismatch" "luke.madstop.com" "luke.madstop.com-bad-public-key.pem"]]]
              (testing policy
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (process-csr-submission! subject csr settings)
                  (is (true? (fs/exists? path)))
                  (fs/delete path)))))))

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
                           :msg "Subject contains unprintable or non-ASCII characters"}
                          (select-keys % [:kind :msg]))]
                     ["subject contains wildcard character" "foo*bar" "bad-subject-name-wildcard.pem"
                      #(=  {:kind :invalid-subject-name
                            :msg "Subject contains a wildcard, which is not allowed: foo*bar"}
                           (select-keys % [:kind :msg]))]]]
              (testing policy
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown+? expected (process-csr-submission! subject csr settings)))
                  (is (false? (fs/exists? path)))))))

          (testing "CSR will be saved when"
            (doseq [[policy subject csr-file expected]
                    [["subject alt name extension exists" "hostwithaltnames" "hostwithaltnames.pem"
                      #(= {:kind :disallowed-extension
                           :msg (str "CSR 'hostwithaltnames' contains subject alternative names "
                                   "(DNS:altname1, DNS:altname2, DNS:altname3), which are disallowed. "
                                   "Use `puppet cert --allow-dns-alt-names sign hostwithaltnames` to sign this request.")}
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
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown+? expected (process-csr-submission! subject csr settings)))
                  (is (true? (fs/exists? path)))
                  (fs/delete path)))))))

      (testing "order of validations"
        (testing "duplicates checked before subject policies"
          (let [settings (assoc settings :allow-duplicate-certs false)
                csr-with-mismatched-name (csr-stream "test-agent")]
            (is (thrown+?
                 [:kind :duplicate-cert
                  :msg "test-agent already has a requested certificate; ignoring certificate request"]
                 (process-csr-submission! "not-test-agent" csr-with-mismatched-name settings)))))
        (testing "subject policies checked before extension & key policies"
          (let [csr-with-disallowed-alt-names (io/input-stream (test-pem-file "hostwithaltnames.pem"))]
            (is (thrown+?
                 [:kind :hostname-mismatch
                  :msg "Instance name \"hostwithaltnames\" does not match requested key \"foo\""]
                 (process-csr-submission! "foo" csr-with-disallowed-alt-names settings)))))))))

(deftest cert-signing-extension-test
  (let [issuer-keys  (utils/generate-key-pair 512)
        issuer-pub   (utils/get-public-key issuer-keys)
        subject-keys (utils/generate-key-pair 512)
        subject-pub  (utils/get-public-key subject-keys)
        subject      "subject"
        subject-dn   (utils/cn subject)]
    (testing "basic extensions are created for an agent"
      (let [csr  (utils/generate-certificate-request subject-keys subject-dn)
            exts (create-agent-extensions csr issuer-pub)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    issuer-pub
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ssl-server-cert ssl-client-cert]}
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
            exts          (create-master-extensions subject
                                                    subject-pub
                                                    issuer-pub
                                                    settings)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    issuer-pub
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ssl-server-cert ssl-client-cert]}
                           {:oid      "2.5.29.15"
                            :critical true
                            :value    #{:digital-signature :key-encipherment}}
                           {:oid      "2.5.29.14"
                            :critical false
                            :value    subject-pub}
                           {:oid      utils/subject-alt-name-oid
                            :critical false
                            :value    {:dns-name ["puppet" "subject"]}}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "additional extensions are created for a master"
      (let [dns-alt-names "onefish,twofish"
            settings      (-> (testutils/master-settings confdir)
                              (assoc :dns-alt-names dns-alt-names)
                              (assoc :csr-attributes (csr-attributes-file "csr_attributes.yaml")))
            exts          (create-master-extensions subject
                                                    subject-pub
                                                    issuer-pub
                                                    settings)
            exts-expected (concat attribute-file-extensions
                                  [{:oid      "2.16.840.1.113730.1.13"
                                    :critical false
                                    :value    netscape-comment-value}
                                   {:oid      "2.5.29.35"
                                    :critical false
                                    :value    {:issuer-dn     nil
                                               :public-key    issuer-pub
                                               :serial-number nil}}
                                   {:oid      "2.5.29.19"
                                    :critical true
                                    :value    {:is-ca false}}
                                   {:oid      "2.5.29.37"
                                    :critical true
                                    :value    [ssl-server-cert ssl-client-cert]}
                                   {:oid      "2.5.29.15"
                                    :critical true
                                    :value    #{:digital-signature :key-encipherment}}
                                   {:oid      "2.5.29.14"
                                    :critical false
                                    :value    subject-pub}
                                   {:oid      "2.5.29.17"
                                    :critical false
                                    :value    {:dns-name ["subject"
                                                          "onefish"
                                                          "twofish"]}}])]
        (is (= (set exts) (set exts-expected)))))

    (testing "A non-puppet OID read from a CSR attributes file is rejected"
      (let [config (assoc (testutils/master-settings confdir)
                          :csr-attributes
                          (csr-attributes-file "insecure_csr_attributes.yaml"))]
        (is (thrown+?
              [:kind :disallowed-extension
               :msg "Found extensions that are not permitted: 1.2.3.4"]
              (create-master-extensions subject subject-pub issuer-pub config)))))

    (testing "invalid DNS alt names are rejected"
      (let [dns-alt-names "*.wildcard"]
        (is (thrown+?
              [:kind :invalid-alt-name
               :msg "Cert subjectAltName contains a wildcard, which is not allowed: *.wildcard"]
              (create-master-extensions subject subject-pub issuer-pub
                                        (assoc (testutils/master-settings confdir)
                                               :dns-alt-names dns-alt-names))))))

    (testing "basic extensions are created for a CA"
      (let [serial        42
            exts          (create-ca-extensions subject-dn
                                                serial
                                                subject-pub)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     (str "CN=" subject)
                                       :public-key    nil
                                       :serial-number (biginteger serial)}}
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
            exts (create-agent-extensions csr issuer-pub)
            exts-expected [{:oid      "2.16.840.1.113730.1.13"
                            :critical false
                            :value    netscape-comment-value}
                           {:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    issuer-pub
                                       :serial-number nil}}
                           {:oid      "2.5.29.19"
                            :critical true
                            :value    {:is-ca false}}
                           {:oid      "2.5.29.37"
                            :critical true
                            :value    [ssl-server-cert ssl-client-cert]}
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
    (is (= "Puppet Server Internal Certificate" netscape-comment-value))))

(deftest ensure-no-authorization-extensions!-test
  (testing "when checking a csr for authorization extensions"
    (let [subject-keys (utils/generate-key-pair 512)
          subject-pub  (utils/get-public-key subject-keys)
          subject      "borges"
          subject-dn   (utils/cn subject)

          pp-auth-ext {:oid (:pp_authorization puppet-short-names)
                       :value "true"
                       :critical false}
          pp-auth-role {:oid (:pp_auth_role puppet-short-names)
                        :value "com"
                        :critical false}
          auth-csr (utils/generate-certificate-request subject-keys subject-dn [pp-auth-ext])
          auth-role-csr (utils/generate-certificate-request subject-keys subject-dn [pp-auth-role])]

      (testing "pp_authorization is caught"
        (is (thrown+-with-msg?
             [:kind :disallowed-extension]
              #".*contains an authorization extension.*borges.*"
             (ensure-no-authorization-extensions! auth-csr))))
      (testing "pp_auth_role is caught"
        (is (thrown+-with-msg?
             [:kind :disallowed-extension]
              #".*contains an authorization extension.*borges.*"
             (ensure-no-authorization-extensions! auth-role-csr)))))))

(deftest validate-subject!-test
  (testing "an exception is thrown when the hostnames don't match"
    (is (thrown+?
          [:kind :hostname-mismatch
           :msg "Instance name \"test-agent\" does not match requested key \"not-test-agent\""]
          (validate-subject!
            "not-test-agent" "test-agent"))))

  (testing "an exception is thrown if the subject name contains a capital letter"
    (is (thrown+?
          [:kind :invalid-subject-name
           :msg "Certificate names must be lower case."]
          (validate-subject! "Host-With-Capital-Letters"
                             "Host-With-Capital-Letters")))))

(deftest validate-dns-alt-names!-test
  (testing "Only DNS alt names are allowed"
    (is (thrown+?
          [:kind :invalid-alt-name
           :msg "Only DNS names are allowed in the Subject Alternative Names extension"]
          (validate-dns-alt-names! {:oid "2.5.29.17"
                                    :critical false
                                    :value {:ip-address ["12.34.5.6"]}}))))

  (testing "No DNS wildcards are allowed"
    (is (thrown+?
          [:kind :invalid-alt-name
           :msg "Cert subjectAltName contains a wildcard, which is not allowed: foo*bar"]
          (validate-dns-alt-names! {:oid "2.5.29.17"
                                    :critical false
                                    :value {:dns-name ["ahostname" "foo*bar"]}})))))

(deftest default-master-dns-alt-names
  (testing "Master certificate has default DNS alt names if none are specified"
    (let [settings  (assoc (testutils/master-settings confdir)
                      :dns-alt-names "")
          pubkey    (-> (utils/generate-key-pair 512)
                        (utils/get-public-key))
          capubkey  (-> (utils/generate-key-pair 512)
                        (utils/get-public-key))
          alt-names (-> (create-master-extensions "master" pubkey capubkey settings)
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
        (create-file-with-perms tmp-file perms)
        (is (= perms (get-file-perms tmp-file)))
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
            (create-file-with-perms tmp-file init-perm)
            (set-file-perms tmp-file change-perm)
            (is (= change-perm (get-file-perms tmp-file)))
            (fs/delete tmp-file)
            (recur (nthnext perms 2))))))))

(deftest create-csr-attrs-exts-test
  (testing "when parsing a csr_attributes file using short names"
    (testing "and the file exists"
      (testing "and it has non-whitelisted OIDs we properly translate the short names."
        (let [extensions (create-csr-attrs-exts (csr-attributes-file "csr_attributes_with_auth.yaml"))
              expected (concat attribute-file-extensions
                               [{:oid "1.3.6.1.4.1.34380.1.3.1" ;; :pp_authorization
                                 :critical false
                                 :value "true"}
                                {:oid "1.3.6.1.4.1.34380.1.3.13" ;; :pp_auth_role
                                 :critical false
                                 :value "com"}])]
          (is (= (set extensions) (set expected)))))
      (testing "and it has whitelisted OIDs we properly translate the short names."
        (let [extensions (create-csr-attrs-exts (csr-attributes-file "csr_attributes.yaml"))]
          (is (= (set extensions) (set attribute-file-extensions))))))
    (testing "and the file doesn't exist"
      (testing "the result is nil"
        (is (nil? (create-csr-attrs-exts "does/not/exist.yaml")))))))
