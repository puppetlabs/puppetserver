(ns puppetlabs.master.services.handler.request-handler-core-test
  (:require [puppetlabs.master.services.handler.request-handler-core :as core]
            [puppetlabs.certificate-authority.core :as cert-utils]
            [clojure.test :refer :all]))

(deftest get-cert-common-name-test
  (testing (str "expected common name can be extracted from the certificate on "
                "a request")
    (let [cert    (-> "./test-resources/config/master/conf/ssl/certs/localhost.pem"
                      cert-utils/pem->certs
                      first)
          request {:ssl-client-cert cert}]
      (is (= "localhost" (core/get-cert-common-name request)))))
  (testing "nil returned for cn when no certificate on request"
    (is (nil? (core/get-cert-common-name {}))))
  (testing "nil returned for cn when certificate on request is nil"
    (is (nil? (core/get-cert-common-name {:ssl-client-cert nil})))))
