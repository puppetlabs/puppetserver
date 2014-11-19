(ns puppetlabs.services.puppet-admin.puppet-admin-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.puppet-admin.puppet-admin-core :as core]
            [schema.test :as schema-test]
            [puppetlabs.certificate-authority.core :as utils]))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def test-resources-dir "./dev-resources/puppetlabs/services/puppet_admin/puppet_admin_core_test")

(defn test-pem-file
  [pem-file-name]
  (str test-resources-dir "/" pem-file-name))

(def localhost-cert
  (utils/pem->cert (test-pem-file "localhost-cert.pem")))

(def other-cert
  (utils/pem->cert (test-pem-file "revoked-agent.pem")))

(defn app
  [config]
  (-> (core/compojure-app
        "/puppet-admin-api"
        config)))

(defn test-request
  [cert]
  {:uri "/puppet-admin-api/v1/hello"
   :request-method :get
   :ssl-client-cert cert})

(deftest certificate-status-test
  (testing "read requests"
    (let [test-app (app {:client-whitelist ["localhost"]})]
      (testing "access allowed when cert is on whitelist"
        (let [response (test-app (test-request localhost-cert))]
          (is (= 200 (:status response)))
          (is (= "hello" (:body response)))))
      (testing "access denied when cert not on whitelist"
        (let [response (test-app (test-request other-cert))]
          (is (= 401 (:status response))))))
    (let [test-app (app {:authorization-required false
                         :client-whitelist       []})]
      (testing "access allowed when auth not required"
        (let [response (test-app (test-request other-cert))]
          (is (= 200 (:status response)))
          (is (= "hello" (:body response))))))))