(ns puppetlabs.services.certificate-authority.certificate-authority-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [schema.test :as schema-test]
    [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/certificate_authority/certificate_authority_int_test")

(use-fixtures :once
              schema-test/validate-schemas
              (jruby-testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def ca-cert
  (bootstrap/pem-file "certs" "ca.pem"))

(def localhost-cert
  (bootstrap/pem-file "certs" "localhost.pem"))

(def localhost-key
  (bootstrap/pem-file "private_keys" "localhost.pem"))

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

(def ca-mount-points
  ["puppet-ca/v1/" ; puppet 4 style
   "production/" ; pre-puppet 4 style
   ])

(defn http-get [path]
  (http-client/get
    (str "https://localhost:8140/" path)
    bootstrap/request-options))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration cert-on-whitelist-test
  (testing "requests made when cert is on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:certificate-authority {:certificate-status
                                 {:client-whitelist ["localhost"]}}
         :authorization {:rules [ {:path "/puppet-ca/v1/certificate"
                                   :type "path"
                                   :allow [ "nonlocalhost" ]}]}}
        (testing "are allowed"
          (doseq [ca-mount-point ca-mount-points
                  endpoint ["certificate_status/localhost"
                            "certificate_statuses/ignored"]]
            (testing (str "for the " endpoint " endpoint")
              (let [response (http-get (str ca-mount-point endpoint))]
                (is (= 200 (:status response))
                    (ks/pprint-to-string response))))))
        (testing "are denied when denied by rule to the certificate endpoint"
          (doseq [ca-mount-point ca-mount-points]
            (let [response (http-get (str ca-mount-point
                                          "certificate/localhost"))]
              (is (= 403 (:status response))
                  (ks/pprint-to-string response)))))))))

(deftest ^:integration cert-not-on-whitelist-test
  (testing "requests made when cert not on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:certificate-authority {:certificate-status
                                 {:client-whitelist ["notlocalhost"]}}
         :authorization         {:rules [{:path  "/puppet-ca/v1/certificate"
                                          :type  "path"
                                          :allow ["localhost"]}]}}
        (testing "are denied"
          (doseq [ca-mount-point ca-mount-points
                  endpoint ["certificate_status/localhost"
                            "certificate_statuses/ignored"]]
            (testing (str "for the " endpoint " endpoint")
              (let [response (http-get (str ca-mount-point endpoint))]
                (is (= 403 (:status response))
                    (ks/pprint-to-string response))))))
        (testing "are allowed when allowed by rule to the certificate endpoint"
          (doseq [ca-mount-point ca-mount-points]
            (let [response (http-get (str ca-mount-point
                                          "certificate/localhost"))]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))))))))

(deftest ^:integration no-whitelist-defined-test
  (testing "requests made when no whitelist is defined"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running
        app
        {:authorization {:rules [ {:path "^/puppet-ca/v1/certificate_status(?:es)?/([^/]+)$"
                                   :type "regex"
                                   :allow [ "$1" ]}]}}
        (testing "are allowed for matching client"
          (doseq [ca-mount-point ca-mount-points
                  endpoint ["certificate_status/localhost"
                            "certificate_statuses/localhost"]]
            (testing (str "for the " endpoint " endpoint")
              (let [response (http-get (str ca-mount-point endpoint))]
                (is (= 200 (:status response))
                    (ks/pprint-to-string response))))))
        (testing "are denied for non-matching client"
          (doseq [ca-mount-point ca-mount-points
                  endpoint ["certificate_status/nonlocalhost"
                            "certificate_statuses/nonlocalhost"]]
            (testing (str "for the " endpoint " endpoint")
              (let [response (http-get (str ca-mount-point endpoint))]
                (is (= 403 (:status response))
                    (ks/pprint-to-string response))))))))))
