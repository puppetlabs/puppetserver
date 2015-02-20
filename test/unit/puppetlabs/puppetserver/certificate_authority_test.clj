(ns puppetlabs.puppetserver.certificate-authority-test
  (:import (java.io StringReader ByteArrayInputStream ByteArrayOutputStream))
  (:require [puppetlabs.puppetserver.certificate-authority :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.certificate-authority.core :as utils]
            [puppetlabs.services.ca.ca-testutils :as testutils]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.slingshot :as sling]
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

(defn test-pem-file
  [pem-file-name]
  (str test-pems-dir "/" pem-file-name))

(defn autosign-conf-file
  [autosign-conf-file-name]
  (str autosign-confs-dir "/" autosign-conf-file-name))

(defn autosign-exe-file
  [autosign-exe-file-name]
  (str autosign-exes-dir "/" autosign-exe-file-name))

(defn csr-attributes-file
  [csr-attributes-file-name]
  (str csr-attributes-dir "/" csr-attributes-file-name))

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

(defmethod assert-expr 'thrown-with-slingshot? [msg form]
  (let [expected (nth form 1)
        body     (nthnext form 2)]
    `(sling/try+
      ~@body
      (do-report {:type :fail :message ~msg :expected ~expected :actual nil})
      (catch map? actual#
        (do-report {:type (if (= actual# ~expected) :pass :fail)
                    :message ~msg
                    :expected ~expected
                    :actual actual#})
        actual#))))

(defn contains-ext?
  "Does the provided extension list contain an extensions with the given OID."
  [ext-list oid]
  (> (count (filter #(= oid (:oid %)) ext-list)) 0))

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
        ruby-load-path ["ruby/puppet/lib" "ruby/facter/lib"]]

    (testing "stdout and stderr are copied to master's log at debug level"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) ruby-load-path)
        (is (logged? #"print to stdout" :debug))
        (is (logged? #"print to stderr" :debug))))

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

    (testing "stdout and stderr are copied to master's log at debug level"
      (logutils/with-test-logging
        (autosign-csr? executable "test-agent" (csr-fn) [])
        (is (logged? #"print to stdout" :debug))
        (is (logged? #"print to stderr" :debug))))

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

    (initialize! settings 512)

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
        (initialize! settings 512)
        (doseq [f files] (is (= "testable string" (slurp f))
                             "File was replaced"))))))

(deftest ca-fail-fast-test
  (testing "Directories not required but are created if absent"
    (doseq [dir [:signeddir :csrdir]]
      (testing dir
        (let [settings (testutils/ca-sandbox! cadir)]
          (fs/delete-dir (get settings dir))
          (is (nil? (initialize! settings 512)))
          (is (true? (fs/exists? (get settings dir))))))))

  (testing "CA public key not required"
    (let [settings (testutils/ca-sandbox! cadir)]
      (fs/delete (:capub settings))
      (is (nil? (initialize! settings 512)))))

  (testing "Exception is thrown when required file is missing"
    (doseq [file required-ca-files]
      (testing file
        (let [settings (testutils/ca-sandbox! cadir)
              path     (get settings file)]
          (fs/delete path)
          (is (thrown-with-msg?
               IllegalStateException
               (re-pattern (str "Missing:\n" path))
               (initialize! settings 512))))))))

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

      (testing "Copied cacert to localcacert when different localcacert present"
        (spit (:localcacert settings) "12345")
        (retrieve-ca-cert! cacert localcacert)
        (is (= (slurp localcacert) cacert-text)
            (str "Unexpected content for localcacert: " localcacert)))

      (testing "Throws exception if no localcacert and no cacert to copy"
        (fs/delete localcacert)
        (let [copy (fs/copy cacert (ks/temp-file))]
          (fs/delete cacert)
          (is (thrown? IllegalStateException
                       (retrieve-ca-cert! cacert localcacert))
              "No exception thrown even though no file existed for copying")
          (fs/copy copy cacert))))))

(deftest initialize-master-ssl!-test
  (let [tmp-confdir (fs/copy-dir confdir (ks/temp-dir))
        settings    (-> (testutils/master-settings tmp-confdir "master")
                        (assoc :dns-alt-names "onefish,twofish"))
        ca-settings (testutils/ca-settings (str tmp-confdir "/ssl/ca"))]

    (retrieve-ca-cert! (:cacert ca-settings) (:localcacert settings))
    (initialize-master-ssl! settings "master" ca-settings 512)

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
                      (dissoc :certdir :requestdir)
                      (vals))]
        (doseq [f files] (spit f "testable string"))
        (initialize-master-ssl! settings "master" ca-settings 512)
        (doseq [f files] (is (= "testable string" (slurp f))
                             "File was replaced"))))

    (testing "Throws an exception if required file is missing"
      (doseq [file required-master-files]
        (testing file
          (let [path (get settings file)
                copy (fs/copy path (ks/temp-file))]
            (fs/delete path)
            (is (thrown-with-msg?
                 IllegalStateException
                 (re-pattern (str "Missing:\n" path))
                 (initialize-master-ssl! settings "master" ca-settings 512)))
            (fs/copy copy path)))))))

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
          (is (thrown-with-slingshot?
               {:type :duplicate-cert
                :message "test-agent already has a requested certificate; ignoring certificate request"}
               (process-csr-submission! "test-agent" (csr-stream "test-agent") settings))))

        (testing "throws exception if certificate already exists"
          (is (thrown-with-slingshot?
               {:type :duplicate-cert
                :message "localhost already has a signed certificate; ignoring certificate request"}
               (process-csr-submission! "localhost"
                                        (io/input-stream (test-pem-file "localhost-csr.pem"))
                                        settings)))
          (is (thrown-with-slingshot?
               {:type :duplicate-cert
                :message "revoked-agent already has a revoked certificate; ignoring certificate request"}
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
                      {:type :hostname-mismatch
                       :message "Instance name \"hostwithaltnames\" does not match requested key \"foo\""}]
                     ["invalid characters in name" "super/bad" "bad-subject-name-1.pem"
                      {:type :invalid-subject-name
                       :message "Subject contains unprintable or non-ASCII characters"}]
                     ["wildcard in name" "foo*bar" "bad-subject-name-wildcard.pem"
                      {:type :invalid-subject-name
                       :message "Subject contains a wildcard, which is not allowed: foo*bar"}]]]
              (testing policy
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown-with-slingshot? exception (process-csr-submission! subject csr settings)))
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
                      {:type :hostname-mismatch
                       :message "Instance name \"hostwithaltnames\" does not match requested key \"foo\""}]
                     ["subject contains invalid characters" "super/bad" "bad-subject-name-1.pem"
                      {:type :invalid-subject-name
                       :message "Subject contains unprintable or non-ASCII characters"}]
                     ["subject contains wildcard character" "foo*bar" "bad-subject-name-wildcard.pem"
                      {:type :invalid-subject-name
                       :message "Subject contains a wildcard, which is not allowed: foo*bar"}]]]
              (testing policy
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown-with-slingshot? expected (process-csr-submission! subject csr settings)))
                  (is (false? (fs/exists? path)))))))

          (testing "CSR will be saved when"
            (doseq [[policy subject csr-file expected]
                    [["subject alt name extension exists" "hostwithaltnames" "hostwithaltnames.pem"
                      {:type :disallowed-extension
                       :message (str "CSR 'hostwithaltnames' contains subject alternative names "
                                     "(DNS:altname1, DNS:altname2, DNS:altname3), which are disallowed. "
                                     "Use `puppet cert --allow-dns-alt-names sign hostwithaltnames` to sign this request.")}]
                     ["unknown extension exists" "meow" "meow-bad-extension.pem"
                      {:type :disallowed-extension
                       :message "Found extensions that are not permitted: 1.9.9.9.9.9.9"}]
                     ["public-private key mismatch" "luke.madstop.com" "luke.madstop.com-bad-public-key.pem"
                      {:type :invalid-signature
                       :message "CSR contains a public key that does not correspond to the signing key"}]]]
              (testing policy
                (let [path (path-to-cert-request (:csrdir settings) subject)
                      csr  (io/input-stream (test-pem-file csr-file))]
                  (is (false? (fs/exists? path)))
                  (is (thrown-with-slingshot? expected (process-csr-submission! subject csr settings)))
                  (is (true? (fs/exists? path)))
                  (fs/delete path)))))))

      (testing "order of validations"
        (testing "duplicates checked before subject policies"
          (let [settings (assoc settings :allow-duplicate-certs false)
                csr-with-mismatched-name (csr-stream "test-agent")]
            (is (thrown-with-slingshot?
                 {:type :duplicate-cert
                  :message "test-agent already has a requested certificate; ignoring certificate request"}
                 (process-csr-submission! "not-test-agent" csr-with-mismatched-name settings)))))
        (testing "subject policies checked before extension & key policies"
          (let [csr-with-disallowed-alt-names (io/input-stream (test-pem-file "hostwithaltnames.pem"))]
            (is (thrown-with-slingshot?
                 {:type :hostname-mismatch
                  :message "Instance name \"hostwithaltnames\" does not match requested key \"foo\""}
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
            exts-expected [
                           {:oid      "2.16.840.1.113730.1.13"
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
                                                  "twofish"]}}
                           ;; These extensions come form the csr_attributes.yaml file
                           {:oid      "1.3.6.1.4.1.34380.1.1.1"
                            :critical false
                            :value    "ED803750-E3C7-44F5-BB08-41A04433FE2E"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.1.4"
                            :critical false
                            :value    "I am undefined but still work"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.2"
                            :critical false
                            :value    "thisisanid"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.3"
                            :critical false
                            :value    "my_ami_image"}
                           {:oid      "1.3.6.1.4.1.34380.1.1.4"
                            :critical false
                            :value    "342thbjkt82094y0uthhor289jnqthpc2290"}]]
        (is (= (set exts) (set exts-expected)))))

    (testing "A non-puppet OID read from a CSR attributes file is rejected"
      (let [config (assoc (testutils/master-settings confdir)
                          :csr-attributes
                          (csr-attributes-file "insecure_csr_attributes.yaml"))]
        (is (thrown-with-slingshot?
              {:type    :disallowed-extension
               :message "Found extensions that are not permitted: 1.2.3.4"}
              (create-master-extensions subject subject-pub issuer-pub config)))))

    (testing "invalid DNS alt names are rejected"
      (let [dns-alt-names "*.wildcard"]
        (is (thrown-with-slingshot?
              {:type    :invalid-alt-name
               :message "Cert subjectAltName contains a wildcard, which is not allowed: *.wildcard"}
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

(deftest validate-subject!-test
  (testing "an exception is thrown when the hostnames don't match"
    (is (thrown-with-slingshot?
          {:type    :hostname-mismatch
           :message "Instance name \"test-agent\" does not match requested key \"not-test-agent\""}
          (validate-subject!
            "not-test-agent" "test-agent"))))

  (testing "an exception is thrown if the subject name contains a capital letter"
    (is (thrown-with-slingshot?
          {:type    :invalid-subject-name
           :message "Certificate names must be lower case."}
          (validate-subject! "Host-With-Capital-Letters"
                             "Host-With-Capital-Letters")))))

(deftest validate-dns-alt-names!-test
  (testing "Only DNS alt names are allowed"
    (is (thrown-with-slingshot?
          {:type    :invalid-alt-name
           :message "Only DNS names are allowed in the Subject Alternative Names extension"}
          (validate-dns-alt-names! {:oid "2.5.29.17"
                                    :critical false
                                    :value {:ip-address ["12.34.5.6"]}}))))

  (testing "No DNS wildcards are allowed"
    (is (thrown-with-slingshot?
          {:type    :invalid-alt-name
           :message "Cert subjectAltName contains a wildcard, which is not allowed: foo*bar"}
          (validate-dns-alt-names! {:oid "2.5.29.17"
                                    :critical false
                                    :value {:dns-name ["ahostname" "foo*bar"]}})))))

(deftest config-test
  (testing "throws meaningful user error when required config not found"
    (is (thrown-with-msg?
         IllegalStateException
         #".*certificate-authority: \{ certificate-status: \{ client-whitelist: \[...] } }.*puppet-server.conf.*"
         (config->ca-settings {})))))

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
