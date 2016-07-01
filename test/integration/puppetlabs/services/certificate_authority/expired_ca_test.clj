(ns puppetlabs.services.certificate-authority.expired-ca-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [puppetlabs.ssl-utils.core :as ssl-utils]
    [puppetlabs.puppetserver.testutils :as testutils :refer
     [ca-cert localhost-cert localhost-key ssl-request-options http-get]]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [schema.test :as schema-test]
    [me.raynes.fs :as fs]
    [cheshire.core :as json]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.ssl-utils.core :as utils]
    [clj-time.core :as time]
    [puppetlabs.puppetserver.certificate-authority :as ca]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/certificate_authority/expired_ca_test")

(use-fixtures :once
              schema-test/validate-schemas
              (testutils/with-puppet-conf (fs/file test-resources-dir "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest expired-ca-test
  (testing "an expired CA cert cannot sign certs"
    (let [keypair (utils/generate-key-pair 512)
          ca-public-key (utils/get-public-key keypair)
          ca-private-key (utils/get-private-key keypair)
          ca-x500-name (utils/cn "expired_ca")
          serial 100
          ca-exts (ca/create-ca-extensions ca-x500-name
                                           serial
                                           ca-public-key)
          ;; Make a 1-year CA cert that expired yesterday
          not-before (time/minus (time/now) (time/days 365))
          not-after (time/minus (time/now) (time/days 1))
          validity {:not-before (.toDate not-before)
                    :not-after (.toDate not-after)}
          ca-cert (utils/sign-certificate
                    ca-x500-name
                    ca-private-key
                    serial
                    (:not-before validity)
                    (:not-after validity)
                    ca-x500-name
                    ca-public-key
                    ca-exts)
          ssl-ca-cert (str test-resources-dir "/ssl/ca/ca_crt.pem")
          ssl-cert (str test-resources-dir "/ssl/ca/ca_crt.pem")
          ssl-key (str test-resources-dir "/ssl/ca/ca_key.pem")

          ;; Gen up a new CSR
          csr-keypair (utils/generate-key-pair 512)
          csr-DN (utils/cn "cant_sign_me")
          csr (ssl-utils/generate-certificate-request csr-keypair csr-DN)
          csr-file (str test-resources-dir "/ssl/certificate_requests/cant_sign_me.pem")]

      (ssl-utils/cert->pem! ca-cert ssl-ca-cert)
      (ssl-utils/cert->pem! ca-cert ssl-cert)
      (ssl-utils/key->pem! ca-private-key ssl-key)
      (ssl-utils/obj->pem! csr csr-file)

      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet {:master-conf-dir test-resources-dir}
         :ssl-cert ssl-cert
         :ssl-ca-cert ssl-ca-cert
         :ssl-key ssl-key}
        (let [response (http-client/put
                         (str "https://localhost:8140"
                              "/puppet-ca/v1/certificate_request/cant_sign_me")
                         {:ssl-cert ssl-cert
                          :ssl-key ssl-key
                          :ssl-ca-cert ssl-ca-cert
                          :as :text
                          :body "{\"desired_state\": \"signed\"}"
                          :headers {"content-type" "text/plain"}})]
          (is (= 200 (:status response))))))))
