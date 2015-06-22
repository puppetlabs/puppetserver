(ns puppetlabs.puppetserver.ringutils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.ringutils :refer :all]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.authorization.rules :as rules]))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def test-resources-dir
  "./dev-resources/puppetlabs/puppetserver/ringutils_test")

(defn test-pem-file
  [pem-file-name]
  (str test-resources-dir "/" pem-file-name))

(def localhost-cert
  (ssl-utils/pem->cert (test-pem-file "localhost-cert.pem")))

(def other-cert
  (ssl-utils/pem->cert (test-pem-file "revoked-agent.pem")))

(def base-handler
  (fn [request]
    {:status 200 :body "hello"}))

(defn build-ring-handler
  [whitelist-settings]
  (-> base-handler
      (wrap-with-cert-whitelist-check whitelist-settings)))

(defn test-request
  [cert]
  {:uri "/foo"
   :request-method :get
   :ssl-client-cert cert})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest wrap-with-cert-whitelist-check-test
  (let [ring-handler (build-ring-handler
                       {:client-whitelist ["localhost"]})]
    (testing "access allowed when cert is on whitelist"
      (let [response (ring-handler (test-request localhost-cert))]
        (is (= 200 (:status response)))
        (is (= "hello" (:body response)))))
    (testing "access denied when cert not on whitelist"
      (let [response (ring-handler (test-request other-cert))]
        (is (= 401 (:status response))))))
  (let [ring-handler (build-ring-handler
                       {:authorization-required false
                        :client-whitelist       []})]
    (testing "access allowed when auth not required"
      (let [response (ring-handler (test-request other-cert))]
        (is (= 200 (:status response)))
        (is (= "hello" (:body response)))))))

(deftest wrap-with-authz-rules-check-test
  (testing "when /etc/puppetlabs/puppetserver/rules.conf does not exist"
    (let [rules-fn (fn [] authz-default-rules)              ; Bypass the rules-path existence check
          ring-handler (wrap-with-authz-rules-check base-handler rules-fn)]
      (testing "defaults to deny access"
        (let [response (ring-handler (test-request localhost-cert))]
          (is (= 403 (:status response)))
          (is (= "Forbidden (determined by rules.conf)" (:body response)))))))
  (testing "when the rules authorize localhost access to /foo"
    (let [rules-fn #(-> rules/empty-rules
                        (rules/add-rule
                          (-> (rules/new-path-rule "/foo")
                              (rules/allow "localhost"))))
          ring-handler (wrap-with-authz-rules-check base-handler rules-fn)]
      (testing "access allowed when cert is localhost"
        (let [response (ring-handler (test-request localhost-cert))]
          (is (= 200 (:status response)))
          (is (= "hello" (:body response))))
        (let [response (ring-handler (test-request other-cert))]
          (is (= 403 (:status response)))
          (is (= "Forbidden (determined by rules.conf)" (:body response))))))))
