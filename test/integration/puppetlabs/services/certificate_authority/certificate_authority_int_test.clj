(ns puppetlabs.services.certificate-authority.certificate-authority-int-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.kitchensink.core :as ks]
   [puppetlabs.ssl-utils.core :as utils]
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
   [puppetlabs.services.ca.certificate-authority-core :refer :all]
   [ring.mock.request :as mock]
   [me.raynes.fs :as fs]
   [clj-time.format :as time-format]
   [clj-time.core :as time])
  (:import (javax.net.ssl SSLException)))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/certificate_authority/certificate_authority_int_test")

(use-fixtures :once
              schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

;; TODO remove the loops that used to use this to test legacy routes
(def ca-mount-points
  ["puppet-ca/v1/"]) ; puppet 4 style

(defn cert-status-request-params
  ([]
   {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
    :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
    :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
    :as :text
    :headers {"content-type" "application/json"}})
  ([body]
   (merge (cert-status-request-params) {:body body})))


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
    (let [server-conf-dir (str test-resources-dir "/ca_true_test/master/conf")
          req-dir (str server-conf-dir "/ca/requests")
          key-pair (ssl-utils/generate-key-pair)
          subjectDN (ssl-utils/cn "test_cert_ca_true")
          serial 1
          public-key (ssl-utils/get-public-key key-pair)
          ca-ext [(ssl-utils/basic-constraints-for-ca)]
          csr (ssl-utils/generate-certificate-request key-pair
                                                      subjectDN
                                                      ca-ext)]
      (fs/mkdir req-dir)
      (ssl-utils/obj->pem! csr (str req-dir "/test_cert_ca_true.pem"))
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
        app
        {:jruby-puppet {:server-conf-dir server-conf-dir}}
        (let [response (http-client/put
                        (str "https://localhost:8140/"
                             "puppet-ca/v1/certificate_status/test_cert_ca_true")
                        {:ssl-cert (str server-conf-dir "/ca/ca_crt.pem")
                         :ssl-key (str server-conf-dir "/ca/ca_key.pem")
                         :ssl-ca-cert (str server-conf-dir "/ca/ca_crt.pem")
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
           (is (= "Not Found" (:body response)))))))))

(deftest ^:integration crl-reloaded-without-server-restart
  (testutils/with-stub-puppet-conf
    (bootstrap/with-puppetserver-running
      app
      {:jruby-puppet
       {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
       :webserver
       {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
        :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
        :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
        :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}}
      (let [key-pair (ssl-utils/generate-key-pair)
            subject "crl_reload"
            subject-dn (ssl-utils/cn subject)
            public-key (ssl-utils/get-public-key key-pair)
            private-key (ssl-utils/get-private-key key-pair)
            private-key-file (ks/temp-file)
            csr (ssl-utils/generate-certificate-request key-pair subject-dn)
            options {:ssl-cert (str bootstrap/server-conf-dir
                                    "/ca/ca_crt.pem")
                     :ssl-key (str bootstrap/server-conf-dir
                                   "/ca/ca_key.pem")
                     :ssl-ca-cert (str bootstrap/server-conf-dir
                                       "/ca/ca_crt.pem")
                     :as :text}
            _ (ssl-utils/key->pem! private-key private-key-file)
            _ (ssl-utils/obj->pem! csr (str bootstrap/server-conf-dir
                                           "/ca/requests/"
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
                                      :ssl-cert (str bootstrap/server-conf-dir
                                                     "/ca/signed/"
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
                            (recur (dec times))))))))))))

(deftest ^:integration revoke-compiler-test
  (testing "Compiler certificate revocation "
    (testutils/with-config-dirs
      {(str test-resources-dir "/infracrl_test/master/conf/ssl") (str bootstrap/server-conf-dir "/ssl")
       (str test-resources-dir "/infracrl_test/master/conf/ca") (str bootstrap/server-conf-dir "/ca")}
      (let [subject "compile-master"
            node-subject "agent-node"
            infra-crl (str bootstrap/server-conf-dir "/ca/infra_crl.pem")
            signed-dir (str bootstrap/server-conf-dir "/ca/signed")
            ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            ca-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
            ca-crl (str bootstrap/server-conf-dir "/ca/ca_crl.pem")]
          (bootstrap/with-puppetserver-running-with-mock-jrubies
           "JRuby mocking is safe here because all of the requests are to the CA
           endpoints, which are implemented in Clojure."
            app
            {:certificate-authority {:enable-infra-crl true}}
            (testing "should update infrastructure CRL"
              (let [ca-cert' (ssl-utils/pem->ca-cert ca-cert ca-key)
                    cm-cert (utils/pem->cert (ca/path-to-cert signed-dir subject))
                    node-cert (utils/pem->cert (ca/path-to-cert signed-dir node-subject))
                    options {:ssl-cert ca-cert
                             :ssl-key ca-key
                             :ssl-ca-cert ca-cert
                             :as :text}
                    cert-status-request (fn [action
                                             certtorevoke]
                                          (http-client/put
                                           (str "https://localhost:8140/"
                                                "puppet-ca/v1/certificate_status/"
                                                certtorevoke)
                                           (merge options
                                                  {:body (str "{\"desired_state\": \""
                                                              action
                                                              "\"}")
                                                   :headers {"content-type"
                                                             "application/json"}})))]
                (testing "Infra CRL should contain the revoked compiler's certificate"
                  (let [revoke-response (cert-status-request "revoked" subject)]
                    ;; If the revocation was successful infra CRL should contain above revoked compiler's cert
                    (is (= 204 (:status revoke-response)))
                    (is (utils/revoked? (utils/pem->ca-crl infra-crl ca-cert') cm-cert))))

                (testing "Infra CRL should NOT contain a revoked non-compiler's certificate"
                  (let [revoke-response (cert-status-request "revoked" node-subject)]
                    (is (= 204 (:status revoke-response)))
                    (is (not (utils/revoked? (utils/pem->ca-crl infra-crl ca-cert') node-cert)))))))

            (testing "Verify correct CRL is returned depending on enable-infra-crl"
              (let [request (mock/request :get "/v1/certificate_revocation_list/mynode")
                    infra-crl-response (handle-get-certificate-revocation-list
                                        request {:cacrl ca-crl
                                                 :infra-crl-path infra-crl
                                                 :enable-infra-crl true})
                    infra-crl-response-body (:body infra-crl-response)
                    full-crl-response (handle-get-certificate-revocation-list
                                       request {:cacrl ca-crl
                                                :infra-crl-path infra-crl
                                                :enable-infra-crl false})
                    full-crl-response-body (:body full-crl-response)]
                (is (map? infra-crl-response))
                (is (= 200 (:status infra-crl-response)))
                (is (= "text/plain" (get-in infra-crl-response [:headers "Content-Type"])))
                (is (string? infra-crl-response-body))
                (is (= infra-crl-response-body (slurp infra-crl)))

                (is (map? full-crl-response))
                (is (= 200 (:status full-crl-response)))
                (is (= "text/plain" (get-in full-crl-response [:headers "Content-Type"])))
                (is (string? full-crl-response-body))
                (is (= full-crl-response-body (slurp ca-crl))))))

          (bootstrap/with-puppetserver-running-with-mock-jrubies
           "JRuby mocking is safe here because all of the requests are to the CA
           endpoints, which are implemented in Clojure."
            app
            {:certificate-authority {:enable-infra-crl true}}
            (testing "Verify infrastructure CRL is returned "
              (let [options {:ssl-ca-cert ca-cert
                             :as :text}
                    crl-response (http-client/get
                                     "https://localhost:8140/puppet-ca/v1/certificate_revocation_list/ca"
                                     (merge options {:headers {"Accept" "text/plain"}}))
                    crl-response-body (:body crl-response)]
                (is (map? crl-response))
                (is (= 200 (:status crl-response)))
                (is (string? crl-response-body))
                (is (= crl-response-body (slurp infra-crl))))))

          (bootstrap/with-puppetserver-running-with-mock-jrubies
           "JRuby mocking is safe here because all of the requests are to the CA
           endpoints, which are implemented in Clojure."
            app
            {:certificate-authority {:enable-infra-crl false}}
            (testing "Verify full CRL is returned "
              (let [options {:ssl-ca-cert ca-cert
                             :as :text}
                    crl-response (http-client/get
                                     "https://localhost:8140/puppet-ca/v1/certificate_revocation_list/ca"
                                     (merge options {:headers {"Accept" "text/plain"}}))
                    crl-response-body (:body crl-response)]
                (is (map? crl-response))
                (is (= 200 (:status crl-response)))
                (is (string? crl-response-body))
                (is (= crl-response-body (slurp ca-crl))))))))))

(deftest ^:integration clean-infrastructure-certs
  (testutils/with-config-dirs
    {(str test-resources-dir "/infracrl_test/master/conf/ssl") (str bootstrap/server-conf-dir "/ssl")
     (str test-resources-dir "/infracrl_test/master/conf/ca") (str bootstrap/server-conf-dir "/ca")}
    (let [infra-inventory-path (str bootstrap/server-conf-dir "/ca/infra_inventory.txt")
          infra-inventory-content (slurp infra-inventory-path)
          ;; We're going to pretend this is an infra cert for this test
          subject2 "agent-node"]
      ;; Add another cert to the infra inventory
      (spit infra-inventory-path (str infra-inventory-content subject2))
      (bootstrap/with-puppetserver-running-with-mock-jrubies
       "JRuby mocking is safe here because all of the requests are to the CA
       endpoints, which are implemented in Clojure."
       app
       {:certificate-authority {:enable-infra-crl true}}
       (let [subject1 "compile-master"
             cert1-path (ca/path-to-cert (str bootstrap/server-conf-dir "/ca/signed") subject1)
             cert1 (utils/pem->cert cert1-path)
             cert2-path (ca/path-to-cert (str bootstrap/server-conf-dir "/ca/signed") subject2)
             cert2 (utils/pem->cert cert2-path)]
       (testing "should update infrastructure CRL with multiple certs"
         (let [ca-cert (ssl-utils/pem->ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                                               (str bootstrap/server-conf-dir "/ca/ca_key.pem"))
               options {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                        :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                        :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                        :as :text}]
           (testing "Infra CRL should contain the revoked compiler's certificate"
             (let [revoke-response (http-client/put
                                     "https://localhost:8140/puppet-ca/v1/clean"
                                     (merge options
                                             {:body (format "{\"certnames\":[\"%s\",\"%s\"]}"
                                                            subject1 subject2)
                                              :headers {"content-type"
                                                        "application/json"}}))]
               ;; If the revocation was successful infra CRL should contain above revoked compiler's cert
               (is (= 200 (:status revoke-response)))
               (is (utils/revoked? (utils/pem->ca-crl
                                    (str bootstrap/server-conf-dir "/ca/infra_crl.pem")
                                    ca-cert)
                                   cert1))
               (is (utils/revoked? (utils/pem->ca-crl
                                    (str bootstrap/server-conf-dir "/ca/infra_crl.pem")
                                    ca-cert)
                                   cert2))
               (is (false? (fs/exists? cert1-path)))
               (is (false? (fs/exists? cert2-path))))))))))))


(deftest ^:integration certificate-status-returns-auth-ext-info
  (testing (str "Validates that the certificate_status endpoint"
                "includes authorization extensions for certs and CSRs")
    (let [request-dir (str bootstrap/server-conf-dir "/ca/requests")
          key-pair (ssl-utils/generate-key-pair)
          subjectDN (ssl-utils/cn "test_cert_with_auth_ext")
          auth-ext-short-name {:oid (:pp_auth_role ca/puppet-short-names)
                               :critical false
                               :value "true"}
          auth-ext-oid {:oid "1.3.6.1.4.1.34380.1.3.1.2"
                        :critical false
                        :value "true"}
          csr (ssl-utils/generate-certificate-request key-pair
                                                      subjectDN
                                                      [auth-ext-short-name
                                                       auth-ext-oid])]
      (fs/mkdirs request-dir)
      (ssl-utils/obj->pem! csr (str request-dir "/test_cert_with_auth_ext.pem"))
      (bootstrap/with-puppetserver-running-with-mock-jrubies
        "JRuby mocking is safe here because all of the requests are to the CA
        endpoints, which are implemented in Clojure."
        app
        {:jruby-puppet
         {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
         :webserver
         {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
          :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
          :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
          :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
         :certificate-authority {:allow-authorization-extensions true}}
        (testing "Auth extensions on a CSR"
          (let [response (http-client/get
                           (str "https://localhost:8140/"
                                "puppet-ca/v1/certificate_status/test_cert_with_auth_ext")
                           (cert-status-request-params))
                auth-exts {"pp_auth_role" "true" "1.3.6.1.4.1.34380.1.3.1.2" "true"}]
            (is (= 200 (:status response)))
            (let [status-body (json/parse-string (:body response))]
              (is (= auth-exts (get status-body "authorization_extensions")))
              (is (= "requested" (get status-body "state"))))))
        (testing "Auth extensions on a cert"
          (let [sign-response (http-client/put
                                (str "https://localhost:8140/"
                                     "puppet-ca/v1/certificate_status/test_cert_with_auth_ext")
                                (cert-status-request-params "{\"desired_state\": \"signed\"}"))
                status-response (http-client/get
                                  (str "https://localhost:8140/"
                                       "puppet-ca/v1/certificate_status/test_cert_with_auth_ext")
                                  (cert-status-request-params))
                auth-exts {"pp_auth_role" "true" "1.3.6.1.4.1.34380.1.3.1.2" "true"}]
            (is (= 204 (:status sign-response)))
            (is (= 200 (:status status-response)))
            (let [status-body (json/parse-string (:body status-response))]
              (is (= auth-exts (get status-body "authorization_extensions")))
              (is (= "signed" (get status-body "state"))))))))
    (fs/delete (str bootstrap/server-conf-dir "/ca/signed/test_cert_with_auth_ext.pem"))))

(deftest csr-api-test
  (testutils/with-stub-puppet-conf
    (bootstrap/with-puppetserver-running-with-config
     app
     (bootstrap/load-dev-config-with-overrides
      {:jruby-puppet
       {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
       :webserver
       {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
        :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
        :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
        :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}})
     (let [request-dir (str bootstrap/server-conf-dir "/ca/requests")
           key-pair (ssl-utils/generate-key-pair)
           subjectDN (ssl-utils/cn "test_cert")
           csr (ssl-utils/generate-certificate-request key-pair subjectDN)
           csr-file (ks/temp-file "test_csr.pem")
           saved-csr (str request-dir "/test_cert.pem")
           url "https://localhost:8140/puppet-ca/v1/certificate_request/test_cert"
           request-opts {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                         :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                         :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                         :as :text
                         :headers {"content-type" "text/plain"}}]
       (ssl-utils/obj->pem! csr csr-file)
       (testing "submit a CSR via the API"
         (let [response (http-client/put
                         url
                         (merge request-opts {:body (slurp csr-file)}))]
           (is (= 200 (:status response)))
           (is (= (slurp csr-file) (slurp saved-csr)))))
       (testing "get a CSR from the API"
         (let [response (http-client/get
                         url
                         request-opts)
               csr (:body response)]
           (is (= 200 (:status response)))
           (is (= (slurp csr-file) csr))))
       (testing "delete a CSR via the API"
         (let [response (http-client/delete
                         url
                         request-opts)]
           (is (= 204 (:status response)))
           (is (not (fs/exists? saved-csr)))))
       (fs/delete csr-file)))))

(deftest ca-expirations-endpoint-test
  (testing "returns expiration dates for all CA certs and CRLs"
    (bootstrap/with-puppetserver-running-with-mock-jrubies
     "JRuby mocking is safe here because all of the requests are to the CA
     endpoints, which are implemented in Clojure."
     app
     {:jruby-puppet
      {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
      :webserver
      {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
       :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
       :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
       :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}}
     (let [response (http-client/get
                     "https://localhost:8140/puppet-ca/v1/expirations"
                     {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                      :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                      :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                      :as :text
                      :headers {"Accept" "application/json"}})]
       (is (= 200 (:status response)))
       (let [body (json/parse-string (:body response))
             ca-exp (get-in body ["ca-certs" "Puppet CA: localhost"])
             crl-exp (get-in body ["crls" "Puppet CA: localhost"])
             formatter (time-format/formatter "YYY-MM-dd'T'HH:mm:ssz")]
         (is (time/after? (time-format/parse formatter ca-exp) (time/now)))
         (is (time/after? (time-format/parse formatter crl-exp) (time/now))))))))

(deftest update-crl-endpoint-test
  (bootstrap/with-puppetserver-running-with-mock-jrubies
   "JRuby mocking is safe here because all of the requests are to the CA
     endpoints, which are implemented in Clojure."
   app
   {:jruby-puppet
    {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
    :webserver
    {:ssl-cert (str bootstrap/master-conf-dir "/ssl/certs/localhost.pem")
     :ssl-key (str bootstrap/master-conf-dir "/ssl/private_keys/localhost.pem")
     :ssl-ca-cert (str bootstrap/master-conf-dir "/ssl/ca/ca_crt.pem")
     :ssl-crl-path (str bootstrap/master-conf-dir "/ssl/crl.pem")}}
   (testing "valid CRL returns 200"
     (let [response (http-client/put
                     "https://localhost:8140/puppet-ca/v1/certificate_revocation_list"
                     {:ssl-cert (str bootstrap/master-conf-dir "/ssl/ca/ca_crt.pem")
                      :ssl-key (str bootstrap/master-conf-dir "/ssl/ca/ca_key.pem")
                      :ssl-ca-cert (str bootstrap/master-conf-dir "/ssl/ca/ca_crt.pem")
                      :as :text
                      :body (slurp (str bootstrap/master-conf-dir "/ssl/crl.pem"))})]
       (is (= 200 (:status response)))))
   (testing "bad data returns 400"
     (let [response (http-client/put
                     "https://localhost:8140/puppet-ca/v1/certificate_revocation_list"
                     {:ssl-cert (str bootstrap/master-conf-dir "/ssl/ca/ca_crt.pem")
                      :ssl-key (str bootstrap/master-conf-dir "/ssl/ca/ca_key.pem")
                      :ssl-ca-cert (str bootstrap/master-conf-dir "/ssl/ca/ca_crt.pem")
                      :as :text
                      :body "Bad data"})]
       (is (= 400 (:status response)))))))
