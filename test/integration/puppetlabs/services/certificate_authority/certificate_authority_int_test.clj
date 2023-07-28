(ns puppetlabs.services.certificate-authority.certificate-authority-int-test
  (:require
    [cheshire.core :as json]
    [clj-time.core :as time]
    [clj-time.format :as time-format]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [me.raynes.fs :as fs]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.puppetserver.certificate-authority :as ca]
    [puppetlabs.puppetserver.testutils :as testutils :refer [http-get]]
    [puppetlabs.rbac-client.protocols.activity :as act-proto]
    [puppetlabs.rbac-client.testutils.dummy-rbac-service :refer [dummy-rbac-service]]
    [puppetlabs.services.ca.ca-testutils :as ca-test-utils]
    [puppetlabs.services.ca.certificate-authority-core :refer [handle-get-certificate-revocation-list]]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [puppetlabs.ssl-utils.core :as ssl-utils]
    [puppetlabs.ssl-utils.simple :as simple]
    [puppetlabs.trapperkeeper.services :as tk-services]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [ring.mock.request :as mock]
    [ring.util.codec :as ring-codec]
    [schema.test :as schema-test])
  (:import (java.util Date)
           (java.util.concurrent TimeUnit)
           (javax.net.ssl SSLException)))

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

(defn create-ca-cert
  [name serial]
  (let [keypair (ssl-utils/generate-key-pair)
        public-key (ssl-utils/get-public-key keypair)
        private-key (ssl-utils/get-private-key keypair)
        x500-name (ssl-utils/cn name)
        validity (ca/cert-validity-dates 3600)
        ca-exts (ca/create-ca-extensions public-key public-key)]
    {:public-key public-key
     :private-key private-key
     :x500-name x500-name
     :certname name
     :cert (ssl-utils/sign-certificate
             x500-name
             private-key
             serial
             (:not-before validity)
             (:not-after validity)
             x500-name
             public-key
             ca-exts)}))

(defn generate-and-sign-a-cert!
  [certname]
  (let [cert-path (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
        key-path (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
        ca-cert-path (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
        key-pair (ssl-utils/generate-key-pair)
        csr (ssl-utils/generate-certificate-request
              key-pair
              (ssl-utils/cn certname))
        csr-path (str bootstrap/server-conf-dir "/ca/requests/" certname ".pem")
        status-url (str "https://localhost:8140/puppet-ca/v1/certificate_status/" certname)
        cert-endpoint (str "https://localhost:8140/puppet-ca/v1/certificate/" certname)
        request-opts {:ssl-cert cert-path
                      :ssl-key key-path
                      :ssl-ca-cert ca-cert-path}]

    (ssl-utils/obj->pem! csr csr-path)
    (http-client/put
      status-url
      (merge request-opts
             {:body "{\"desired_state\": \"signed\"}"
              :headers {"content-type" "application/json"}}))
    (let [cert-request (http-client/get cert-endpoint {:ssl-ca-cert ca-cert-path})
          private-key-file (ks/temp-file)
          public-key-file (ks/temp-file)]
      (ssl-utils/key->pem! (ssl-utils/get-public-key key-pair) public-key-file)
      (ssl-utils/key->pem! (ssl-utils/get-private-key key-pair) private-key-file)
      {:signed-cert (slurp (:body cert-request))
       :public-key public-key-file
       :private-key private-key-file})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest reporting-activity-handles-errors
  (let [cert-path (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
        key-path (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
        ca-cert-path (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
        crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")
        test-service (tk-services/service
                      act-proto/ActivityReportingService
                      []
                      (report-activity! [_this body]
                        (throw (Exception. "Foo"))))]
    (testing "returns expiration dates for all CA certs and CRLs"
      (testutils/with-stub-puppet-conf
        (bootstrap/with-puppetserver-running-with-services
         app
         (concat (bootstrap/services-from-dev-bootstrap) [test-service])
         {:jruby-puppet
          {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
          :webserver
          {:ssl-cert cert-path
           :ssl-key key-path
           :ssl-ca-cert ca-cert-path
           :ssl-crl-path crl-path}}
         (let [certname "test_cert"
               csr (ssl-utils/generate-certificate-request
                    (ssl-utils/generate-key-pair)
                    (ssl-utils/cn certname))
               csr-path (str bootstrap/server-conf-dir "/ca/requests/" certname ".pem")
               signed-cert-path (str bootstrap/server-conf-dir "/ca/signed/" certname ".pem")
               status-url (str "https://localhost:8140/puppet-ca/v1/certificate_status/" certname)
               request-opts {:ssl-cert cert-path
                             :ssl-key key-path
                             :ssl-ca-cert ca-cert-path}]

           (ssl-utils/obj->pem! csr csr-path)
           (testing "Sign the waiting CSR"
             (logutils/with-test-logging
               (let [response (http-client/put
                               status-url
                               (merge request-opts
                                      {:body "{\"desired_state\": \"signed\"}"
                                       :headers {"content-type" "application/json"}}))]
                 (is (= 204 (:status response)))
                 (is (logged? #"Reporting CA event failed with: Foo" :error))
                 (is (logged? #"Payload.*commit" :error))
                 (is (fs/exists? signed-cert-path))
                 (is (logged? #"Entity localhost signed 1 certificate.*" :info)))))

           (testing "Revoke the cert"
             (logutils/with-test-logging
               (let [response (http-client/put
                               status-url
                               (merge request-opts
                                      {:body "{\"desired_state\": \"revoked\"}"
                                       :headers {"content-type" "application/json" "X-Authentication" "test"}}))]
                 (is (= 204 (:status response)))
                 (is (logged? #"Reporting CA event failed with: Foo" :error))
                 (is (logged? #"Payload.*commit" :error))
                 (is (logged? #"Entity localhost revoked 1 certificate.*" :info)))))))))))

(deftest new-cert-signing-respects-agent-renewal-support-indication
  (testutils/with-config-dirs
    {(str test-resources-dir "/infracrl_test/master/conf/ssl") (str bootstrap/server-conf-dir "/ssl")
     (str test-resources-dir "/infracrl_test/master/conf/ca") (str bootstrap/server-conf-dir "/ca")}
    (let [cert-path (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
          key-path (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
          ca-cert-path (str bootstrap/server-conf-dir "/ca/ca_crt.pem")]
      (testing "with a puppetserver configured for auto-renewal"
        (testutils/with-stub-puppet-conf
          (bootstrap/with-puppetserver-running
            app
            {:certificate-authority { :allow-auto-renewal true}
             :jruby-puppet
             {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
             :webserver
             {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
              :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
              :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
              :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}}
            (testing "signs a cert with a short ttl when the capability indicator is present"
              (let [certname (ks/rand-str :alpha-lower 8)
                    csr (ssl-utils/generate-certificate-request
                          (ssl-utils/generate-key-pair)
                          (ssl-utils/cn certname)
                          []
                          [{:oid "1.3.6.1.4.1.34380.1.3.2" :value true}])
                    csr-path (ks/temp-file "test_csr.pem")
                    signed-cert-path (str bootstrap/server-conf-dir "/ca/signed/" certname ".pem")
                    status-url (str "https://localhost:8140/puppet-ca/v1/certificate_status/" certname)
                    url (str "https://localhost:8140/puppet-ca/v1/certificate_request/" certname)
                    request-opts {:ssl-cert cert-path
                                  :ssl-key key-path
                                  :ssl-ca-cert ca-cert-path}]

                  (ssl-utils/obj->pem! csr csr-path)
                  (testing "submit a CSR via the API"
                    (let [response (http-client/put
                                     url
                                     (merge request-opts {:body (slurp csr-path)
                                                          :headers {"x-puppet-version" "8.2.0"}}))]
                      (is (= 200 (:status response)))))
                  (testing "Sign the waiting CSR"
                    (let [response (http-client/put
                                     status-url
                                     (merge request-opts
                                            {:body "{\"desired_state\": \"signed\"}"
                                             :headers {"content-type" "application/json"}}))]
                        (is (= 204 (:status response)))
                        (is (fs/exists? signed-cert-path))
                        (let [signed-cert (ssl-utils/pem->cert signed-cert-path)]
                          (testing "new not-after should be 89 days (and some fraction) away"
                            (let [diff (- (.getTime (.getNotAfter signed-cert)) (.getTime (Date.)))
                                  days (.convert TimeUnit/DAYS diff TimeUnit/MILLISECONDS)]
                              (is (= 89 days)))))))))
            (testing "signs a cert with a long ttl when the capability indicator is not present"
              (let [certname (ks/rand-str :alpha-lower 8)
                    csr (ssl-utils/generate-certificate-request
                          (ssl-utils/generate-key-pair)
                          (ssl-utils/cn certname))
                    csr-path (ks/temp-file "test_csr.pem")
                    signed-cert-path (str bootstrap/server-conf-dir "/ca/signed/" certname ".pem")
                    status-url (str "https://localhost:8140/puppet-ca/v1/certificate_status/" certname)
                    url (str "https://localhost:8140/puppet-ca/v1/certificate_request/" certname)
                    request-opts {:ssl-cert cert-path
                                  :ssl-key key-path
                                  :ssl-ca-cert ca-cert-path}]

                (ssl-utils/obj->pem! csr csr-path)
                (testing "submit a CSR via the API"
                  (let [response (http-client/put
                                   url
                                   (merge request-opts {:body (slurp csr-path)
                                                        :headers {"x-puppet-version" "7.1.8"}}))]
                    (is (= 200 (:status response)))))
                (testing "Sign the waiting CSR"
                  (let [response (http-client/put
                                   status-url
                                   (merge request-opts
                                          {:body "{\"desired_state\": \"signed\"}"
                                           :headers {"content-type" "application/json"}}))]
                    (is (= 204 (:status response)))
                    (is (fs/exists? signed-cert-path))
                    (let [signed-cert (ssl-utils/pem->cert signed-cert-path)]
                      (testing "new not-after should be 5 years (and some fraction) away"
                        (let [diff (- (.getTime (.getNotAfter signed-cert)) (.getTime (Date.)))
                              days (.convert TimeUnit/DAYS diff TimeUnit/MILLISECONDS)]
                          (is (= (- (* 365 5) 1) days)))))))))))))))
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
  (testing "Validates that the server rejects a csr for signing that has the v3 CA:TRUE extension"
    (let [server-conf-dir (str test-resources-dir "/ca_true_test/master/conf")
          req-dir (str server-conf-dir "/ca/requests")
          key-pair (ssl-utils/generate-key-pair)
          subjectDN (ssl-utils/cn "test_cert_ca_true")
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
  (testing "client not able to unintentionally get access to CA endpoint by double-encoding request uri"
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
    (with-redefs [act-proto/report-activity! (fn [_ _] nil)]
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
                                              (catch SSLException _
                                                true))]
            (is (loop [times 30]
                  (cond
                    (ssl-exception-for-request?) true
                    (zero? times) false
                    :else (do
                            (Thread/sleep 500)
                            (recur (dec times)))))))))))))

(deftest ^:integration revoke-compiler-test
  (testing "Compiler certificate revocation "
   (with-redefs [act-proto/report-activity! (fn [_ _] nil)]
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
                    cm-cert (ssl-utils/pem->cert (ca/path-to-cert signed-dir subject))
                    node-cert (ssl-utils/pem->cert (ca/path-to-cert signed-dir node-subject))
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
                    (is (ssl-utils/revoked? (ssl-utils/pem->ca-crl infra-crl ca-cert') cm-cert))))

                (testing "Infra CRL should NOT contain a revoked non-compiler's certificate"
                  (let [revoke-response (cert-status-request "revoked" node-subject)]
                    (is (= 204 (:status revoke-response)))
                    (is (not (ssl-utils/revoked? (ssl-utils/pem->ca-crl infra-crl ca-cert') node-cert)))))))

            (testing "Verify correct CRL is returned depending on enable-infra-crl"
              (let [request (mock/request :get "/v1/certificate_revocation_list/mynode")
                    ca-settings (ca-test-utils/ca-settings "")
                    infra-crl-response (handle-get-certificate-revocation-list
                                        request (assoc ca-settings
                                                  :cacrl ca-crl
                                                  :infra-crl-path infra-crl
                                                  :enable-infra-crl true))
                    infra-crl-response-body (:body infra-crl-response)
                    full-crl-response (handle-get-certificate-revocation-list
                                        request (assoc ca-settings
                                                 :cacrl ca-crl
                                                 :infra-crl-path infra-crl
                                                 :enable-infra-crl false))
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
                (is (= crl-response-body (slurp ca-crl)))))))))))

(deftest ^:integration clean-infrastructure-certs
  (with-redefs [act-proto/report-activity! (fn [_ _] nil)]
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
             cert1 (ssl-utils/pem->cert cert1-path)
             cert2-path (ca/path-to-cert (str bootstrap/server-conf-dir "/ca/signed") subject2)
             cert2 (ssl-utils/pem->cert cert2-path)]
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
                 (is (ssl-utils/revoked? (ssl-utils/pem->ca-crl
                                         (str bootstrap/server-conf-dir "/ca/infra_crl.pem")
                                         ca-cert)
                                     cert1))
                 (is (ssl-utils/revoked? (ssl-utils/pem->ca-crl
                                      (str bootstrap/server-conf-dir "/ca/infra_crl.pem")
                                      ca-cert)
                                     cert2))
                 (is (false? (fs/exists? cert1-path)))
                 (is (false? (fs/exists? cert2-path)))))))))))))


(deftest ^:integration certificate-status-returns-auth-ext-info
  (testing "Validates that the certificate_status endpoint includes authorization extensions for certs and CSRs"
    (with-redefs [act-proto/report-activity! (fn [_ _] nil)]
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
    (fs/delete (str bootstrap/server-conf-dir "/ca/signed/test_cert_with_auth_ext.pem")))))

(deftest ^:integration certificate-inventory-file-management
  (testing "Validates that the certificate_status endpoint includes authorization extensions for certs and CSRs"
    (with-redefs [act-proto/report-activity! (fn [_ _] nil)]
      (let [inventory-path (str bootstrap/server-conf-dir "/ca/inventory.txt")
            inventory-content (slurp inventory-path)
            request-dir (str bootstrap/server-conf-dir "/ca/requests")
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
          (testing "Adding to a very large inventory file works correctly"
            (spit inventory-path inventory-content)
            ;; internally the inventory append uses a 64K buffer, so make sure it is larger than that.
            (loop [hostnames (map #(format "host-%d-name.thing.to.take-up-spaces\n" %) (range 0 10000))
                   hostname (first hostnames)]
              (when hostname
                (spit inventory-path hostname :append true)
                (recur (rest hostnames)
                       (second hostnames))))

            (let [sign-response (http-client/put
                                  (str "https://localhost:8140/"
                                       "puppet-ca/v1/certificate_status/test_cert_with_auth_ext")
                                  (cert-status-request-params "{\"desired_state\": \"signed\"}"))]
              (is (= 204 (:status sign-response)))
              ;; inventory file should have "test_cert_with_auth_ext" in it
              (let [new-inventory-contents (slurp inventory-path)]
                (is (re-find #"test_cert_with_auth_ext" new-inventory-contents)))))))
      (fs/delete (str bootstrap/server-conf-dir "/ca/signed/test_cert_with_auth_ext.pem")))))

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

(deftest csr-api-puppet-version-test
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
            subjectDN (ssl-utils/cn "test_version_cert")
            csr (ssl-utils/generate-certificate-request key-pair subjectDN)
            csr-file (ks/temp-file "test_version_csr.pem")
            saved-csr (str request-dir "/test_version_cert.pem")
            url "https://localhost:8140/puppet-ca/v1/certificate_request/test_version_cert"
            request-opts {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                          :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                          :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                          :as :text
                          :headers {"content-type" "text/plain"
                                    "x-puppet-version" "8.2.0"}}]
        (ssl-utils/obj->pem! csr csr-file)
        (testing "submit a CSR via the API"
          (let [response (http-client/put
                           url
                           (merge request-opts {:body (slurp csr-file)}))]
            (is (= 200 (:status response)))
            (fs/delete csr-file)))
        (testing "delete a CSR via the API"
          (let [response (http-client/delete
                           url
                           request-opts)]
            (is (= 204 (:status response)))
            (is (not (fs/exists? saved-csr)))))))))

(deftest csr-activity-service-cert
  (let [reported-activity (atom [])
        test-service (tk-services/service act-proto/ActivityReportingService
                       []
                       (report-activity! [_this body]
                         (swap! reported-activity conj body)))
        cert-path (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
        key-path (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
        ca-cert-path (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
        crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")]

    (testutils/with-stub-puppet-conf
        (bootstrap/with-puppetserver-running-with-services
          app
          (concat (bootstrap/services-from-dev-bootstrap) [test-service])
          (bootstrap/load-dev-config-with-overrides
            {:jruby-puppet
              {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
             :webserver
              {:ssl-cert cert-path
               :ssl-key key-path
               :ssl-ca-cert ca-cert-path
               :ssl-crl-path crl-path}})
          (let [certname "test_cert"
                csr (ssl-utils/generate-certificate-request
                     (ssl-utils/generate-key-pair)
                     (ssl-utils/cn certname))
                csr-path (str bootstrap/server-conf-dir "/ca/requests/" certname ".pem")
                status-url (str "https://localhost:8140/puppet-ca/v1/certificate_status/" certname)
                request-opts {:ssl-cert cert-path
                              :ssl-key key-path
                              :ssl-ca-cert ca-cert-path}
                requester-name (-> (ssl-utils/pem->ca-cert cert-path key-path)
                                   (ssl-utils/get-subject-from-x509-certificate)
                                   (ssl-utils/x500-name->CN))]


            (ssl-utils/obj->pem! csr csr-path)

            (testing "Sign the waiting CSR"
              (let [response (http-client/put
                              status-url
                              (merge request-opts
                                     {:body "{\"desired_state\": \"signed\"}"
                                      :headers {"content-type" "application/json"}}))
                    activity-events (get-in (first @reported-activity) [:commit :events])
                    msg-matcher (re-pattern (str "Entity " requester-name " signed 1 certificate: " certname))]
                (is (= 204 (:status response)))
                (is (re-find msg-matcher (:message (first activity-events))))
                (is (= 1 (count @reported-activity)))))

            (testing "Revoke the cert"
              (let [response (http-client/put
                              status-url
                              (merge request-opts
                                     {:body "{\"desired_state\": \"revoked\"}"
                                      :headers {"content-type" "application/json"}}))
                    activity-events (get-in (second @reported-activity) [:commit :events])
                    msg-matcher (re-pattern (str "Entity " requester-name " revoked 1 certificate: " certname))]
                (is (= 204 (:status response)))
                (is (re-find msg-matcher (:message (first activity-events))))
                (is (= 2 (count @reported-activity)))))

            (fs/delete csr-path))))))

(deftest csr-activity-service-token
  (testutils/with-config-dirs
    {(str test-resources-dir "/infracrl_test/master/conf/ssl") (str bootstrap/server-conf-dir "/ssl")
     (str test-resources-dir "/infracrl_test/master/conf/ca") (str bootstrap/server-conf-dir "/ca")}
    (let [reported-activity (atom [])
          test-service (tk-services/service
                         act-proto/ActivityReportingService
                         []
                         (report-activity! [_this body]
                           (swap! reported-activity conj body)))]

      (testutils/with-stub-puppet-conf
          (bootstrap/with-puppetserver-running-with-services
            app
            (concat (bootstrap/services-from-dev-bootstrap) [test-service dummy-rbac-service])
            (bootstrap/load-dev-config-with-overrides
              {:jruby-puppet
                {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
                :webserver
                {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
                 :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
                 :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                 :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
                :authorization {:version 1
                                :rules [{:match-request
                                         {:path "/"
                                          :type "path"}
                                         :allow [{:rbac {:permission "cert_requests:accept_reject:*"}}]
                                         :sort-order 1
                                         :name "cert"}]}})
            (let [request-dir (str bootstrap/server-conf-dir "/ca/requests")
                  key-pair (ssl-utils/generate-key-pair)
                  certname "test_cert"
                  subjectDN (ssl-utils/cn certname)
                  csr (ssl-utils/generate-certificate-request key-pair subjectDN)
                  saved-csr (str request-dir "/test_cert.pem")
                  status-url (str "https://localhost:8140/puppet-ca/v1/certificate_status/" certname)
                  request-opts {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                                :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                                :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")}]
                 (ssl-utils/obj->pem! csr saved-csr)

              (testing "Sign the waiting CSR"
                (let [response (http-client/put
                                status-url
                                (merge request-opts {:body "{\"desired_state\": \"signed\"}"
                                                      :headers {"content-type" "application/json" "X-Authentication" "test"}}))
                      activity-events (get-in (first @reported-activity) [:commit :events])
                      msg-matcher (re-pattern (str "Entity test_user signed 1 certificate: " certname))]
                  (is (= 204 (:status response)))
                  (is (re-find msg-matcher (:message (first activity-events))))
                  (is (= 1 (count @reported-activity)))))

              (testing "Revoke the cert"
                (let [response (http-client/put status-url
                                 (merge request-opts {:body "{\"desired_state\": \"revoked\"}"
                                                      :headers {"content-type" "application/json" "X-Authentication" "test"}}))
                      activity-events (get-in (second @reported-activity) [:commit :events])
                      msg-matcher (re-pattern (str "Entity test_user revoked 1 certificate: " certname))]

                  (is (= 204 (:status response)))
                  (is (re-find msg-matcher (:message (first activity-events))))
                  (is (= 2 (count @reported-activity)))))

              (fs/delete saved-csr)))))))

(deftest csr-activity-service-default

  (let [reported-activity (atom [])
        test-service (tk-services/service
                  act-proto/ActivityReportingService
                  []
                  (report-activity! [_this body]
                    (swap! reported-activity conj body)))]

  (testutils/with-stub-puppet-conf
      (bootstrap/with-puppetserver-running-with-services
        app
        (concat (bootstrap/services-from-dev-bootstrap) [test-service])

        (update-in (bootstrap/load-dev-config-with-overrides
        {:jruby-puppet
          {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver {:host "0.0.0.0"
                       :port 8140}}) [:webserver] dissoc :ssl-host :ssl-port)

        (let [request-dir (str bootstrap/server-conf-dir "/ca/requests")
              key-pair (ssl-utils/generate-key-pair)
              certname "test_cert"
              subjectDN (ssl-utils/cn certname)
              csr (ssl-utils/generate-certificate-request key-pair subjectDN)
              saved-csr (str request-dir "/test_cert.pem")
              status-url (str "http://localhost:8140/puppet-ca/v1/certificate_status/" certname)
              request-opts {}]
          (ssl-utils/obj->pem! csr saved-csr)

          (testing "Sign the waiting CSR"
            (let [response (http-client/put
                            status-url
                            (merge request-opts {:body "{\"desired_state\": \"signed\"}"
                                                  :headers {"content-type" "application/json"}}))
                  activity-events (get-in (first @reported-activity) [:commit :events])
                  msg-matcher (re-pattern (str "Entity CA signed 1 certificate: " certname))]
              (is (= 204 (:status response)))
              (is (re-find msg-matcher (:message (first activity-events))))
              (is (= 1 (count @reported-activity)))))

          (testing "Revoke the cert"
            (let [response (http-client/put
              status-url
              (merge request-opts {:body "{\"desired_state\": \"revoked\"}"
                                    :headers {"content-type" "application/json"}}))
              activity-events (get-in (second @reported-activity) [:commit :events])
              msg-matcher (re-pattern (str "Entity CA revoked 1 certificate: " certname))]
              (is (= 204 (:status response)))
              (is (re-find msg-matcher (:message (first activity-events))))
              (is (= 2 (count @reported-activity)))))
              
              (fs/delete saved-csr))))))

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
    {:ssl-cert (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
     :ssl-key (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
     :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
     :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}}
   (testing "valid CRL returns 200"
     (let [response (http-client/put
                     "https://localhost:8140/puppet-ca/v1/certificate_revocation_list"
                     {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                      :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                      :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                      :as :text
                      :body (slurp (str bootstrap/server-conf-dir "/ssl/crl.pem"))})]
       (is (= 200 (:status response)))))
   (testing "bad data returns 400"
     (let [response (http-client/put
                     "https://localhost:8140/puppet-ca/v1/certificate_revocation_list"
                     {:ssl-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                      :ssl-key (str bootstrap/server-conf-dir "/ca/ca_key.pem")
                      :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                      :as :text
                      :body "Bad data"})]
       (is (= 400 (:status response)))))))

(deftest ca-certificate-renew-endpoint-test
  (testing "with the feature enabled"
    (testing "with allow-header-cert-info = false (default)"
      (testing "returns a 200 OK response when feature is enabled,
            a certificate is present in the request, and that cert matches
            the signing cert"
        (bootstrap/with-puppetserver-running-with-mock-jrubies
          "JRuby mocking is safe here because all of the requests are to the CA
          endpoints, which are implemented in Clojure."
          app
          {:jruby-puppet
           {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver
           {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
            :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
           :certificate-authority
           {:allow-auto-renewal true}}
          (let [generated-cert-info (generate-and-sign-a-cert! "foobar")
                signed-cert-file (ks/temp-file)
                _ (spit signed-cert-file (:signed-cert generated-cert-info))
                _ (Thread/sleep 1000) ;; ensure some time has passed so the timestamps are different
                response (http-client/post
                           "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                           {:ssl-cert    (str signed-cert-file)
                            :ssl-key     (str (:private-key generated-cert-info))
                            :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                            :as          :text})]
            (is (= 200 (:status response)))
            (let [renewed-cert-pem (:body response)
                  renewed-cert-file (ks/temp-file)
                  _ (spit renewed-cert-file renewed-cert-pem)
                  renewed-cert (ssl-utils/pem->cert renewed-cert-file)
                  signed-cert (ssl-utils/pem->cert signed-cert-file)]
              (testing "serial number has been incremented"
                (is (< (.getSerialNumber signed-cert) (.getSerialNumber renewed-cert))))
              (testing "not before time stamps have changed"
                (is (true? (.before (.getNotBefore signed-cert) (.getNotBefore renewed-cert)))))
              (testing "new not-after is earlier than before"
                (is (true? (.after (.getNotAfter signed-cert) (.getNotAfter renewed-cert)))))
              (testing "new not-after should be 89 days (and some fraction) away"
                (let [diff (- (.getTime (.getNotAfter renewed-cert)) (.getTime (Date.)))
                      days (.convert TimeUnit/DAYS diff TimeUnit/MILLISECONDS)]
                  (is (= 89 days))))))))

      (testing "Honors non-default auto-renewal-cert-ttl"
        (bootstrap/with-puppetserver-running-with-mock-jrubies
          "JRuby mocking is safe here because all of the requests are to the CA
          endpoints, which are implemented in Clojure."
          app
          {:jruby-puppet
           {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver
           {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
            :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
           :certificate-authority
           {:allow-auto-renewal true
            :auto-renewal-cert-ttl "42d"}}
          (let [generated-cert-info (generate-and-sign-a-cert! "foobar")
                signed-cert-file (ks/temp-file)
                _ (spit signed-cert-file (:signed-cert generated-cert-info))
                _ (Thread/sleep 1000) ;; ensure some time has passed so the timestamps are different
                response (http-client/post
                           "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                           {:ssl-cert    (str signed-cert-file)
                            :ssl-key     (str (:private-key generated-cert-info))
                            :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                            :as          :text})]
            (is (= 200 (:status response)))
            (let [renewed-cert-pem (:body response)
                  renewed-cert-file (ks/temp-file)
                  _ (spit renewed-cert-file renewed-cert-pem)
                  renewed-cert (ssl-utils/pem->cert renewed-cert-file)
                  signed-cert (ssl-utils/pem->cert signed-cert-file)]
              (testing "serial number has been incremented"
                (is (< (.getSerialNumber signed-cert) (.getSerialNumber renewed-cert))))
              (testing "not before time stamps have changed"
                (is (true? (.before (.getNotBefore signed-cert) (.getNotBefore renewed-cert)))))
              (testing "new not-after is earlier than before"
                (is (true? (.after (.getNotAfter signed-cert) (.getNotAfter renewed-cert)))))
              (testing "new not-after should be 41 days (and some fraction) away"
                (let [diff (- (.getTime (.getNotAfter renewed-cert)) (.getTime (Date.)))
                      days (.convert TimeUnit/DAYS diff TimeUnit/MILLISECONDS)]
                  (is (= 41 days))))))))

      (testing "returns a 400 bad request response when the ssl-client-cert is not present"
        (bootstrap/with-puppetserver-running-with-mock-jrubies
          "JRuby mocking is safe here because all of the requests are to the CA
          endpoints, which are implemented in Clojure."
          app
          {:jruby-puppet
           {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver
           {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
            :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
           :certificate-authority
           {:allow-auto-renewal true}}
          (let [response (http-client/post
                           "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                           {:ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                            :as          :text})]
            (is (= 400 (:status response)))
            (is (= "No certificate found in renewal request" (:body response)))))))

    (testing "with allow-header-cert-info = true"
      (testing "returns a 200 OK response when the feature is enabled,
            a cert is supplied in header and the signing certificate matches"
        (bootstrap/with-puppetserver-running-with-mock-jrubies
          "JRuby mocking is safe here because all of the requests are to the CA
          endpoints, which are implemented in Clojure."
          app
          {:jruby-puppet
           {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver
           {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
            :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
           :certificate-authority
           {:allow-auto-renewal true}
           :authorization
           {:allow-header-cert-info true}}
          (let [generated-cert-info (generate-and-sign-a-cert! "foobar")
                signed-cert-file (ks/temp-file)
                _ (spit signed-cert-file (:signed-cert generated-cert-info))
                header-cert (ring-codec/url-encode (:signed-cert generated-cert-info))
                _ (Thread/sleep 1000)
                response (http-client/post
                           "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                           {:headers     {"x-client-cert" header-cert}
                            :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                            :as          :text})]
            (is (= 200 (:status response)))
            (let [renewed-cert-pem (:body response)
                  renewed-cert-file (ks/temp-file)
                  _ (spit renewed-cert-file renewed-cert-pem)
                  renewed-cert (ssl-utils/pem->cert renewed-cert-file)
                  signed-cert (ssl-utils/pem->cert signed-cert-file)]
              (testing "serial number has been incremented"
                (is (< (.getSerialNumber signed-cert) (.getSerialNumber renewed-cert))))
              (testing "not before time stamps have changed"
                (is (true? (.before (.getNotBefore signed-cert) (.getNotBefore renewed-cert)))))
              (testing "new not-after is earlier than before"
                (is (true? (.after (.getNotAfter signed-cert) (.getNotAfter renewed-cert)))))
              (testing "new not-after should be 89 days (and some fraction) away"
                (let [diff (- (.getTime (.getNotAfter renewed-cert)) (.getTime (Date.)))
                      days (.convert TimeUnit/DAYS diff TimeUnit/MILLISECONDS)]
                  (is (= 89 days))))))))

      (testing "returns a 400 bad request response when the feature is enabled,
             and a bogus cert is supplied in the header"
        (bootstrap/with-puppetserver-running-with-mock-jrubies
          "JRuby mocking is safe here because all of the requests are to the CA
          endpoints, which are implemented in Clojure."
          app
          {:jruby-puppet
           {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver
           {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
            :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
           :certificate-authority
           {:allow-auto-renewal true}
           :authorization
           {:allow-header-cert-info true}}
          (let [header-cert "abadstring"
                response (http-client/post
                           "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                           {:headers     {"x-client-cert" header-cert}
                            :ssl-cert    (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
                            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
                            :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                            :as          :text})]
            (is (= 400 (:status response)))
            (is (= "No certs found in PEM read from x-client-cert" (:body response))))))

      (testing "returns a 403 forbidden response when a certificate is present in the request,
               but that cert does not match the signing cert"
        (bootstrap/with-puppetserver-running-with-mock-jrubies
          "JRuby mocking is safe here because all of the requests are to the CA
          endpoints, which are implemented in Clojure."
          app
          {:jruby-puppet
           {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
           :webserver
           {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
            :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
            :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
            :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
           :certificate-authority
           {:allow-auto-renewal true}
           :authorization
           {:allow-header-cert-info true}}
          (let [ca-cert (create-ca-cert "ca-nomatch" 42)
                ca-cert-file (ks/temp-file)
                _ (ssl-utils/cert->pem! (:cert ca-cert) ca-cert-file)
                cert-1 (simple/gen-cert "localhost" ca-cert 4 {:extensions [(ssl-utils/authority-key-identifier (:cert ca-cert))]})
                cert-1-file (ks/temp-file)
                _ (ssl-utils/cert->pem! (:cert cert-1) cert-1-file)
                header-cert (ring-codec/url-encode (slurp cert-1-file))
                private-key-file (ks/temp-file)
                _ (ssl-utils/key->pem! (:private-key cert-1) private-key-file)
                response (http-client/post
                           "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                           {:headers     {"x-client-cert" header-cert}
                            :ssl-cert    (str cert-1-file)
                            :ssl-key     (str private-key-file)
                            :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                            :as          :text})]
            (is (= 403 (:status response)))
            (is (= "Certificate present, but does not match signature" (:body response))))))))

  (testing "with the feature disabled"
    (testing "returns a 404 not found response when feature is disabled"
      (bootstrap/with-puppetserver-running-with-mock-jrubies
        "JRuby mocking is safe here because all of the requests are to the CA
        endpoints, which are implemented in Clojure."
        app
        {:jruby-puppet
         {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}
         :webserver
         {:ssl-cert     (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
          :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
          :ssl-ca-cert  (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
          :ssl-crl-path (str bootstrap/server-conf-dir "/ssl/crl.pem")}
         :certificate-authority
         {:allow-auto-renewal false}}
        (let [response (http-client/post
                         "https://localhost:8140/puppet-ca/v1/certificate_renewal"
                         {:ssl-cert    (str bootstrap/server-conf-dir "/ssl/certs/localhost.pem")
                          :ssl-key      (str bootstrap/server-conf-dir "/ssl/private_keys/localhost.pem")
                          :ssl-ca-cert (str bootstrap/server-conf-dir "/ca/ca_crt.pem")
                          :as          :text})]
          (is (= 404 (:status response))))))))
