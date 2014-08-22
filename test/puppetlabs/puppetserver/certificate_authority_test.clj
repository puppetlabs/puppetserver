(ns puppetlabs.puppetserver.certificate-authority-test
  (:import (java.io StringReader ByteArrayInputStream ByteArrayOutputStream))
  (:require [puppetlabs.puppetserver.certificate-authority :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.certificate-authority.core :as utils]
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

(def ssldir "./dev-resources/config/master/conf/ssl")
(def cadir (str ssldir "/ca"))
(def cacert (str cadir "/ca_crt.pem"))
(def cakey (str cadir "/ca_key.pem"))
(def capub (str cadir "/ca_pub.pem"))
(def cacrl (str cadir "/ca_crl.pem"))
(def csrdir (str cadir "/requests"))
(def signeddir (str cadir "/signed"))

(defn ca-test-settings
  "CA configuration settings with defaults appropriate for testing.
   All file and directory paths will be rooted at the static 'cadir'
   in dev-resources, unless a different `cadir` is provided."
  ([] (ca-test-settings cadir))
  ([cadir]
     {:autosign              true
      :allow-duplicate-certs false
      :ca-name               "test ca"
      :ca-ttl                1
      :cacrl                 (str cadir "/ca_crl.pem")
      :cacert                (str cadir "/ca_crt.pem")
      :cakey                 (str cadir "/ca_key.pem")
      :capub                 (str cadir "/ca_pub.pem")
      :cert-inventory        (str cadir "/inventory.txt")
      :csrdir                (str cadir "/requests")
      :signeddir             (str cadir "/signed")
      :serial                (str cadir "/serial")
      :ruby-load-path        []}))

(defn master-test-settings
  "Master configuration settings with defaults appropriate for testing.
   All file and directory paths will be rooted at the static 'ssldir'
   in dev-resources, unless a different `ssldir` is provided."
  ([] (master-test-settings ssldir "localhost"))
  ([ssldir hostname]
     {:certdir       (str ssldir "/certs")
      :dns-alt-names "onefish,twofish"
      :hostcert      (str ssldir "/certs/" hostname ".pem")
      :hostprivkey   (str ssldir "/private_keys/" hostname ".pem")
      :hostpubkey    (str ssldir "/public_keys/" hostname ".pem")
      :localcacert   (str ssldir "/certs/ca.pem")
      :requestdir    (str ssldir "/certificate_requests")}))

(def ca-cert-subject
  (-> cacert
      utils/pem->cert
      get-subject))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn assert-subject [o subject]
  (is (= subject (-> o .getSubjectX500Principal .getName))))

(defn assert-issuer [o issuer]
  (is (= issuer (-> o .getIssuerX500Principal .getName))))

(defn tmp-whitelist! [& lines]
  (let [whitelist (ks/temp-file)]
    (doseq [line lines]
      (spit whitelist (str line "\n") :append true))
    (str whitelist)))

(defn tmp-serial-file! []
  (doto (str (ks/temp-file))
    initialize-serial-file!))

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

(defmacro thrown-with-slingshot?
  [expected-map f]
  `(sling/try+
    ~f
    false
    (catch map? actual-map#
      (= actual-map# ~expected-map))))

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
        (doto "dev-resources/config/master/conf/autosign-whitelist.conf"
          (assert-no-autosign "aaa")
          (assert-autosign "bbb123")
          (assert-autosign "one_2.red")
          (assert-autosign "1.blacK.6")
          (assert-no-autosign "black.white")
          (assert-no-autosign "coffee")
          (assert-no-autosign "coffee#tea")
          (assert-autosign "qux")))))

  (testing "executable"
    (testing "ruby script"
      (let [executable      "dev-resources/config/master/conf/ruby-autosign-executable"
            csr-fn          #(csr-stream "test-agent")
            ruby-load-path  ["ruby/puppet/lib" "ruby/facter/lib"]]

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

    (testing "bash script"
      (let [executable "dev-resources/config/master/conf/bash-autosign-executable"
            csr-fn     #(csr-stream "test-agent")]

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
            (is (false? (autosign-csr? executable "foo" (csr-fn) [])))))))))

(deftest save-certificate-request!-test
  (testing "requests are saved to disk"
    (let [csr    (utils/pem->csr (path-to-cert-request csrdir "test-agent"))
          path   (path-to-cert-request csrdir "foo")]
      (try
        (is (false? (fs/exists? path)))
        (save-certificate-request! "foo" csr csrdir)
        (is (true? (fs/exists? path)))
        (is (= (get-certificate-request csrdir "foo")
               (get-certificate-request csrdir "test-agent")))
        (finally
          (fs/delete path))))))

(deftest autosign-certificate-request!-test
  (let [now                (time/epoch)
        two-years          (* 60 60 24 365 2)
        settings           (assoc (ca-test-settings)
                             :serial (tmp-serial-file!)
                             :cert-inventory (str (ks/temp-file))
                             :ca-ttl two-years)
        csr                (utils/pem->csr (path-to-cert-request csrdir "test-agent"))
        expected-cert-path (path-to-cert (:signeddir settings) "test-agent")]
    (try
      ;; Fix the value of "now" so we can reliably test the dates
      (time/do-at now
       (autosign-certificate-request! "test-agent" csr settings))

      (testing "requests are autosigned and saved to disk"
        (is (fs/exists? expected-cert-path)))

      (let [cert (utils/pem->cert expected-cert-path)]
        (testing "The subject name on the agent's cert"
          (assert-subject cert "CN=test-agent"))

        (testing "The cert is issued by the name on the CA's cert"
          (assert-issuer cert ca-cert-subject))

        (testing "certificate has not-before/not-after dates based on $ca-ttl"
          (let [not-before (time-coerce/from-date (.getNotBefore cert))
                not-after (time-coerce/from-date (.getNotAfter cert))]
            (testing "not-before is 1 day before now"
              (is (= (time/minus now (time/days 1)) not-before)))
            (testing "not-after is 2 years from now"
              (is (= (time/plus now (time/years 2)) not-after))))))

      (finally
        (fs/delete expected-cert-path)))))

(deftest get-certificate-revocation-list-test
  (testing "`get-certificate-revocation-list` returns a valid CRL file."
    (let [crl (-> (get-certificate-revocation-list cacrl)
                  StringReader.
                  utils/pem->crl)]
      (assert-issuer crl "CN=Puppet CA: localhost"))))

(deftest initialize-ca!-test
  (let [settings (ca-test-settings (ks/temp-dir))]

    (initialize-ca! settings 512)

    (testing "Generated SSL file"
      (doseq [file (vals (settings->cadir-paths settings))]
        (testing file
          (is (fs/exists? file)))))

    (testing "cacrl"
      (let [crl (-> settings :cacrl utils/pem->crl)]
        (assert-issuer crl "CN=test ca")
        (testing "has at least one expected extension - crl number"
          (let [crl-number (utils/get-extension-value crl "2.5.29.20")]
            (is (= 0 crl-number))))))

    (testing "cacert"
      (let [cert (-> settings :cacert utils/pem->cert)]
        (is (utils/certificate? cert))
        (assert-subject cert "CN=test ca")
        (assert-issuer cert "CN=test ca")
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

    (testing "Inventory file should have been created."
      (is (fs/exists? (:cert-inventory settings))))

    (testing "Serial number file file should have been created."
      (is (fs/exists? (:serial settings))))))

(deftest initialize-master!-test
  (let [ssldir          (ks/temp-dir)
        master-settings (master-test-settings ssldir "master")
        serial          (tmp-serial-file!)
        inventory       (str (ks/temp-file))
        signeddir       (str (ks/temp-dir))
        capubkey        (utils/pem->public-key capub)]

    (initialize-master! master-settings "master" "Puppet CA: localhost"
                        (utils/pem->private-key cakey)
                        capubkey
                        (utils/pem->cert cacert)
                        512 serial inventory signeddir 1)

    (testing "Generated SSL file"
      (doseq [file (vals (settings->ssldir-paths master-settings))]
        (testing file
          (is (fs/exists? file)))))

    (testing "hostcert"
      (let [hostcert (-> master-settings :hostcert utils/pem->cert)]
        (is (utils/certificate? hostcert))
        (assert-subject hostcert "CN=master")
        (assert-issuer hostcert "CN=Puppet CA: localhost")

        (testing "has alt names extension"
          (let [dns-alt-names (-> (utils/get-extension hostcert "2.5.29.17")
                                  (get-in [:value :dns-name])
                                  set)]
            (is (= #{"master" "onefish" "twofish"} dns-alt-names)
                "The Subject Alternative Names extension should contain the
                  master's actual hostname and the hostnames in $dns-alt-names")))

        (testing "is also saved in the CA's $signeddir"
          (let [signedpath (path-to-cert signeddir "master")]
            (is (fs/exists? signedpath))
            (is (= hostcert (utils/pem->cert signedpath)))))))

    (testing "localcacert"
      (let [cacert (-> master-settings :localcacert utils/pem->cert)]
        (is (utils/certificate? cacert))
        (assert-subject cacert "CN=Puppet CA: localhost")
        (assert-issuer cacert "CN=Puppet CA: localhost")))

    (testing "hostprivkey"
      (let [key (-> master-settings :hostprivkey utils/pem->private-key)]
        (is (utils/private-key? key))
        (is (= 512 (utils/keylength key)))))

    (testing "hostpubkey"
      (let [key (-> master-settings :hostpubkey utils/pem->public-key)]
        (is (utils/public-key? key))
        (is (= 512 (utils/keylength key)))))))

(deftest initialize!-test
  (let [ssldir          (ks/temp-dir)
        ca-settings     (ca-test-settings (str ssldir "/ca"))
        master-settings (master-test-settings ssldir "master")]

    (testing "Generated SSL file"
      (try
        (initialize! ca-settings master-settings "master" 512)
        (doseq [file (concat (vals (settings->cadir-paths ca-settings))
                             (vals (settings->ssldir-paths master-settings)))]
          (testing file
            (is (fs/exists? file))))
        (finally
          (fs/delete-dir ssldir))))

    (testing "Does not create new files if they all exist"
      (let [directories [:csrdir :signeddir :requestdir :certdir]
            all-files   (merge (settings->cadir-paths ca-settings)
                               (settings->ssldir-paths master-settings))
            no-dirs     (vals (apply dissoc all-files directories))]
        (try
          ;; Create the directory structure and dummy files by hand
          (create-parent-directories! (vals all-files))
          (doseq [d directories] (fs/mkdir (d all-files)))
          (doseq [file no-dirs]
            (spit file "unused content"))

          (initialize! ca-settings master-settings "master" 512)

          (doseq [file no-dirs]
            (is (= "unused content" (slurp file))
                "Existing file was replaced"))
          (finally
            (fs/delete-dir ssldir)))))

    (testing "Throws an exception if only some of the files exist"
      (try
        ;; Create all the files and directories, then delete some
        (initialize! ca-settings master-settings "master" 512)
        (fs/delete-dir (:signeddir ca-settings))
        (fs/delete (:capub ca-settings))

        ;; Verify exception is thrown with message that contains
        ;; the paths for both the missing and found files
        (initialize! ca-settings master-settings "master" 512)
        (catch IllegalStateException e
          (doseq [file (vals (settings->cadir-paths ca-settings))]
            (is (true? (.contains (.getMessage e) file)))))

        (finally
          (fs/delete-dir ssldir))))

    (testing "Keylength"
      (doseq [[message f expected]
              [["can be configured"
                (partial initialize! ca-settings master-settings "master" 512)
                512]
               ["has a default value"
                (partial initialize! ca-settings master-settings "master")
                utils/default-key-length]]]
        (testing message
          (try
            (f)
            (is (= expected (-> ca-settings :cakey
                                utils/pem->private-key utils/keylength)))
            (is (= expected (-> ca-settings :capub
                                utils/pem->public-key utils/keylength)))
            (is (= expected (-> master-settings :hostprivkey
                                utils/pem->private-key utils/keylength)))
            (is (= expected (-> master-settings :hostpubkey
                                utils/pem->public-key utils/keylength)))
            (finally
              (fs/delete-dir ssldir))))))))

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

; If the locking is deleted from `next-serial-number!`, this test will hang,
; which is not as nice as simply failing ...
; This seems to happen due to a deadlock caused by concurrently reading and
; writing to the same file (via `slurp` and `spit`)
(deftest next-serial-number-threadsafety
  (testing "next-serial-number! is thread-safe and
            never returns a duplicate serial number"
    (let [serial-file (doto (str (ks/temp-file)) (spit "0001"))
          serials     (atom [])

          ; spin off a new thread for each CPU
          promises    (for [_ (range (ks/num-cpus))]
                        (let [p (promise)]
                          (future
                            ; get a bunch of serial numbers and keep track of them
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

(deftest process-csr-submission!-test
  (let [settings (assoc (ca-test-settings)
                   :serial (tmp-serial-file!)
                   :cert-inventory (str (ks/temp-file)))]
    (testing "throws an exception if a CSR already exists for that subject"
      (is (thrown-with-slingshot?
           {:type    :duplicate-cert
            :message "test-agent already has a requested certificate; ignoring certificate request"}
           (process-csr-submission! "test-agent" (csr-stream "test-agent") settings)))

      (testing "unless $allow-duplicate-certs is true"
        (let [settings  (assoc settings :allow-duplicate-certs true)
              cert-path (path-to-cert (:signeddir settings) "test-agent")]
          (logutils/with-test-logging
            (is (false? (fs/exists? cert-path)))
            (process-csr-submission! "test-agent" (csr-stream "test-agent") settings)
            (is (logged? #"test-agent already has a requested certificate; new certificate will overwrite it" :info))
            (is (true? (fs/exists? cert-path))))
          (fs/delete cert-path))))

    (testing "throws an exception if a certificate already exists for that subject"
      (is (thrown-with-slingshot?
           {:type    :duplicate-cert
            :message "localhost already has a signed certificate; ignoring certificate request"}
           (process-csr-submission! "localhost" (csr-stream "test-agent") settings)))

      (testing "unless $allow-duplicate-certs is true"
        (let [settings (assoc settings :allow-duplicate-certs true :autosign false)
              csr-path (path-to-cert-request (:csrdir settings) "localhost")]
          (logutils/with-test-logging
            (is (false? (fs/exists? csr-path)))
            (process-csr-submission! "localhost" (csr-stream "test-agent") settings)
            (is (logged? #"localhost already has a signed certificate; new certificate will overwrite it" :info))
            (is (true? (fs/exists? csr-path))))
          (fs/delete csr-path))))))

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
      (let [dns-alt-names "onefish,twofish"
            exts          (create-master-extensions subject
                                                    subject-pub
                                                    issuer-pub
                                                    dns-alt-names)
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
                           {:oid      "2.5.29.17"
                            :critical false
                            :value    {:dns-name ["subject"
                                                  "onefish"
                                                  "twofish"]}}]]
        (is (= (set exts) (set exts-expected)))))

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

    (testing "basic extensions are created for a CRL"
      (let [crl-num       0
            exts          (create-crl-extensions issuer-pub)
            exts-expected [{:oid      "2.5.29.35"
                            :critical false
                            :value    {:issuer-dn     nil
                                       :public-key    issuer-pub
                                       :serial-number nil}}
                           {:oid      "2.5.29.20"
                            :critical false
                            :value    (biginteger crl-num)}]]
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

(deftest validate-csr-hostname!-test
  (testing "an exception is thrown when the hostnames don't match"
    (is (thrown-with-slingshot?
          {:type    :hostname-mismatch
           :message "Instance name \"test-agent\" does not match requested key \"not-test-agent\""}
          (validate-csr-subject!
            "not-test-agent" (utils/pem->csr (path-to-cert-request csrdir "test-agent"))))))

  (testing "an exception is thrown if the subject name contains a capital letter"
    (is (thrown-with-slingshot?
          {:type    :invalid-subject-name
           :message "Certificate names must be lower case."}
          (validate-csr-subject!
            "Host-With-Capital-Letters"
            (utils/pem->csr "dev-resources/Host-With-Capital-Letters.pem"))))))
