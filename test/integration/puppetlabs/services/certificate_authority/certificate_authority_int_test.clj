(ns puppetlabs.services.certificate-authority.certificate-authority-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [puppetlabs.puppetserver.testutils :as testutils :refer
     [ca-cert localhost-cert localhost-key ssl-request-options http-get]]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [schema.test :as schema-test]
    [me.raynes.fs :as fs]
    [cheshire.core :as json]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.ssl-utils.core :as ssl-utils]
    [puppetlabs.puppetserver.certificate-authority :as ca]
    [me.raynes.fs :as fs])
  (:import (javax.net.ssl SSLException)))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/certificate_authority/certificate_authority_int_test")

(use-fixtures :once
              schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def ca-mount-points
  ["puppet-ca/v1/" ; puppet 4 style
   "production/" ; pre-puppet 4 style
   ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration cert-on-whitelist-test
  (testing "requests made when cert is on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
        app
        {:certificate-authority {:certificate-status
                                 {:client-whitelist ["localhost"]}}
         :authorization         {:version 1
                                 :rules [{:match-request
                                          {:path "/puppet-ca/v1/certificate"
                                           :type "path"}
                                          :allow ["nonlocalhost"]
                                          :sort-order 1
                                          :name "cert"}]}}
        (testing "are allowed"
          (doseq [ca-mount-point ca-mount-points
                  endpoint ["certificate_status/localhost"
                            "certificate_statuses/ignored"]]
            (testing (str "for the " endpoint " endpoint")
              (let [response (http-get (str ca-mount-point endpoint))]
                (is (= 200 (:status response))
                    (ks/pprint-to-string response))))))
        (logutils/with-test-logging
          (testing "are denied when denied by rule to the certificate endpoint"
            (doseq [ca-mount-point ca-mount-points]
              (let [response (http-get (str ca-mount-point
                                            "certificate/localhost"))]
                (is (= 403 (:status response))
                    (ks/pprint-to-string response))))))))))

(deftest ^:integration cert-not-on-whitelist-test
  (testing "requests made when cert not on whitelist"
    (logutils/with-test-logging
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
        app
        {:certificate-authority {:certificate-status
                                 {:client-whitelist ["notlocalhost"]}}
         :authorization         {:version 1
                                 :rules [{:match-request
                                          {:path "/puppet-ca/v1/certificate"
                                           :type "path"}
                                          :allow ["localhost"]
                                          :sort-order 1
                                          :name "cert"}]}}
        (logutils/with-test-logging
          (testing "are denied"
            (doseq [ca-mount-point ca-mount-points
                    endpoint ["certificate_status/localhost"
                              "certificate_statuses/ignored"]]
              (testing (str "for the " endpoint " endpoint")
                (let [response (http-get (str ca-mount-point endpoint))]
                  (is (= 403 (:status response))
                      (ks/pprint-to-string response)))))))
        (testing "are allowed when allowed by rule to the certificate endpoint"
          (doseq [ca-mount-point ca-mount-points]
            (let [response (http-get (str ca-mount-point
                                          "certificate/localhost"))]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response)))))))))

(deftest ^:integration empty-whitelist-defined-test
  (testing "requests made when no whitelist is defined"
    (logutils/with-test-logging
     (bootstrap/with-puppetserver-running-with-mock-jrubies
      "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
      app
      {:certificate-authority {:certificate-status
                               {:client-whitelist []}}
       :authorization {:version 1
                       :rules [{:match-request
                                {:path "^/puppet-ca/v1/certificate_status(?:es)?/([^/]+)$"
                                 :type "regex"}
                                :allow ["$1"]
                                :sort-order 1
                                :name "cert status"}]}}
      (testing "are allowed for matching client"
        (doseq [ca-mount-point ca-mount-points
                endpoint ["certificate_status/localhost"
                          "certificate_statuses/localhost"]]
          (testing (str "for the " endpoint " endpoint")
            (let [response (http-get (str ca-mount-point endpoint))]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response))))))
      (logutils/with-test-logging
       (testing "are denied for non-matching client"
         (doseq [ca-mount-point ca-mount-points
                 endpoint ["certificate_status/nonlocalhost"
                           "certificate_statuses/nonlocalhost"]]
           (testing (str "for the " endpoint " endpoint")
             (let [response (http-get (str ca-mount-point endpoint))]
               (is (= 403 (:status response))
                   (ks/pprint-to-string response)))))))))))

(deftest ^:integration no-whitelist-defined-test
  (testing "requests made when no whitelist is defined"
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
      app
      {:authorization {:version 1
                       :rules [{:match-request
                                {:path "^/puppet-ca/v1/certificate_status(?:es)?/([^/]+)$"
                                 :type "regex"}
                                :allow ["$1"]
                                :sort-order 1
                                :name "cert status"}]}}
      (testing "are allowed for matching client with no encoded characters"
        (doseq [ca-mount-point ca-mount-points
                endpoint ["certificate_status/localhost"
                          "certificate_statuses/localhost"]]
          (testing (str "for the " endpoint " endpoint")
            (let [response (http-get (str ca-mount-point endpoint))]
              (is (= 200 (:status response))
                  (ks/pprint-to-string response))))))
      (testing "are allowed for matching client with some encoded characters"
        (let [response (http-get (str "puppet-ca/v1/certificate_status/"
                                      "%6cocalhost"))]
          (is (= 200 (:status response))
              (ks/pprint-to-string response))
          (is (= "localhost" (-> response
                                :body
                                json/parse-string
                                (get "name")))))
        (let [response (http-get (str "production/certificate_status/"
                                      "%6cocalhost"))]
          (is (= 200 (:status response))
              (ks/pprint-to-string response))
          (is (= "localhost" (-> response
                                 :body
                                 json/parse-string
                                 (get "name"))))))
      (logutils/with-test-logging
        (testing "are denied for non-matching client"
          (doseq [ca-mount-point ca-mount-points
                  endpoint ["certificate_status/nonlocalhost"
                            "certificate_statuses/nonlocalhost"]]
            (testing (str "for the " endpoint " endpoint")
              (let [response (http-get (str ca-mount-point endpoint))]
                (is (= 403 (:status response))
                    (ks/pprint-to-string response))))))))))

(deftest ^:integration certificate-with-ca-true-extension-refused
  (testing (str "Validates that the server rejects a csr for signing"
                " that has the v3 CA:TRUE extension")
    (let [master-conf-dir (str test-resources-dir "/ca_true_test/master/conf")
          req-dir (str master-conf-dir "/ssl/ca/requests")
          key-pair (ssl-utils/generate-key-pair)
          subjectDN (ssl-utils/cn "test_cert_ca_true")
          serial 1
          public-key (ssl-utils/get-public-key key-pair)
          ca-ext (ca/create-ca-extensions subjectDN
                                          serial
                                          public-key)
          csr (ssl-utils/generate-certificate-request key-pair
                                                      subjectDN
                                                      ca-ext)]
      (fs/mkdir req-dir)
      (ssl-utils/obj->pem! csr (str req-dir "/test_cert_ca_true.pem"))
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
        app
        {:jruby-puppet {:master-conf-dir master-conf-dir}}
        (let [response (http-client/put
                       (str "https://localhost:8140"
                            "puppet-ca/v1/certificate_status/test_cert_ca_true")
                       {:ssl-cert (str master-conf-dir "/ssl/ca/ca_crt.pem")
                        :ssl-key (str master-conf-dir "/ssl/ca/ca_key.pem")
                        :ssl-ca-cert (str master-conf-dir "/ssl/ca/ca_crt.pem")
                        :as :text
                        :body "{\"desired_state\": \"signed\"}"
                        :headers {"content-type" "application/json"}})]
        (is (= 409 (:status response)))
        (is (.startsWith (:body response) "Found extensions"))
        (is (.contains (:body response) "2.5.29.19"))
        (fs/delete-dir req-dir))))))

(deftest ^:integration double-encoded-request-not-allowed
  (testing (str "client not able to unintentionally get access to CA endpoint "
                "by double-encoding request uri")
    ;; The following tests are intended to show that a client is not able
    ;; to unintentionally gain access to info for a different client by
    ;; double-encoding a character in the client name portion of the
    ;; request.  This test is a bit odd in that:
    ;;
    ;; 1) The 'path' for the auth-rule needs to have an allow access control
    ;;    entry which uses the regular expression format, e.g., "/%6cocalhost/"
    ;;    instead of just "/%6cocalhost", because tk-auth only permits a
    ;;    percent character to be used in the entry name for the regular
    ;;    expression format.  Other formats generate an exception because the
    ;;    percent character is not legal for a domain type entry.
    ;;
    ;; 2) The requests still fail with an HTTP 404 (Not Found) error because
    ;;    the subject parameter is destructured in the certificate_status route
    ;;    definition...
    ;;
    ;;    (ANY ["/certificate_status/" :subject] [subject]
    ;;
    ;;    ... and a comidi route evaluation will fail to match a 'subject' that
    ;;    has a percent character in the name.
    ;;
    ;; This test may be more useful at the point tk-auth and comidi were to
    ;; more generally handle the presence of a percent character.
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
     app
     {:authorization {:version 1
                      :rules [{:match-request
                               {:path (str "/puppet-ca/v1/certificate_status/"
                                           "%6cocalhost")
                                :type "path"}
                               :allow ["/%6cocalhost/"]
                               :sort-order 1
                               :name "cert status"}]}}
     (let [ca-cert (bootstrap/get-ca-cert-for-running-server)
           client-cert (bootstrap/get-cert-signed-by-ca-for-running-server
                        ca-cert
                        "%6cocalhost")
           ssl-context (bootstrap/get-ssl-context-for-cert-map
                        ca-cert
                        client-cert)]
       (testing "for a puppet v4 style CA request"
         (let [response (http-client/get
                         (str "https://localhost:8140/puppet-ca/v1/"
                              "certificate_status/%256cocalhost")
                         {:ssl-context ssl-context
                          :as :text})]
           (is (= 404 (:status response))
               (ks/pprint-to-string response))
           (is (= "Not Found" (:body response)))))
       (testing "for a legacy CA request"
         (let [response (http-client/get
                         (str "https://localhost:8140/production/"
                              "certificate_status/%256cocalhost")
                         {:ssl-context ssl-context
                          :as :text})]
           (is (= 404 (:status response))
               (ks/pprint-to-string response))
           (is (= "Not Found" (:body response)))))))))

(deftest ^:integration crl-reloaded-without-server-restart
         (bootstrap/with-puppetserver-running
           app
           {:jruby-puppet
            {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
            :webserver
            {:ssl-cert (str bootstrap/master-conf-dir "/ssl/certs/localhost.pem")
             :ssl-key (str bootstrap/master-conf-dir "/ssl/private_keys/localhost.pem")
             :ssl-ca-cert (str bootstrap/master-conf-dir "/ssl/ca/ca_crt.pem")
             :ssl-crl-path (str bootstrap/master-conf-dir "/ssl/crl.pem")}}
           (let [key-pair (ssl-utils/generate-key-pair)
                 subject "crl_reload"
                 subject-dn (ssl-utils/cn subject)
                 public-key (ssl-utils/get-public-key key-pair)
                 private-key (ssl-utils/get-private-key key-pair)
                 private-key-file (ks/temp-file)
                 csr (ssl-utils/generate-certificate-request key-pair subject-dn)
                 options {:ssl-cert (str bootstrap/master-conf-dir
                                         "/ssl/ca/ca_crt.pem")
                          :ssl-key (str bootstrap/master-conf-dir
                                        "/ssl/ca/ca_key.pem")
                          :ssl-ca-cert (str bootstrap/master-conf-dir
                                            "/ssl/ca/ca_crt.pem")
                          :as :text}
                 _ (ssl-utils/key->pem! private-key private-key-file)
                 _ (ssl-utils/obj->pem! csr (str bootstrap/master-conf-dir
                                                "/ssl/ca/requests/"
                                                subject
                                                ".pem"))
                 cert-status-request (fn [action]
                                       (http-client/put
                                         (str "https://localhost:8140/"
                                              "puppet-ca/v1/certificate_status/"
                                              subject)
                                         (merge options
                                                {:body (str "{\"desired_state\": \""
                                                            action
                                                            "\"}")
                                                 :headers {"content-type"
                                                           "application/json"}})))
                 client-request #(http-client/get
                                  "https://localhost:8140/status/v1/services"
                                   (merge options
                                          {:ssl-key (str private-key-file)
                                           :ssl-cert (str bootstrap/master-conf-dir
                                                          "/ssl/ca/signed/"
                                                          subject
                                                          ".pem")}))]
             (testing "node certificate request can be signed successfully"
               (let [sign-response (cert-status-request "signed")]
                 (is (= 204 (:status sign-response)))))
             (testing "node request before revocation is successful"
               (let [node-response-before-revoke (client-request)]
                 (is (= 200 (:status node-response-before-revoke)))))
             (testing "node certificate can be successfully revoked"
               (let [revoke-response (cert-status-request "revoked")]
                 (is (= 204 (:status revoke-response)))))
             (testing "node request after revocation fails"
               (let [ssl-exception-for-request? #(try
                                                   (client-request)
                                                   false
                                                   (catch SSLException e
                                                     true))]
                 (is (loop [times 30]
                       (cond
                         (ssl-exception-for-request?) true
                         (zero? times) false
                         :else (do
                                 (Thread/sleep 500)
                                 (recur (dec times)))))))))))
