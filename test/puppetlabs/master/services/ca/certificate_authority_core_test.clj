(ns puppetlabs.master.services.ca.certificate-authority-core-test
  (:require [puppetlabs.master.services.ca.certificate-authority-core :refer :all]
            [clojure.test :refer :all]))

(deftest crl-endpoint-test
  (testing "implementation of the CRL endpoint"
    (let [response (handle-get-certificate-revocation-list {:ssl-dir "./test-resources/config/master/conf/ssl"})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (= "text/plain" (get-in response [:headers "Content-Type"])))
      (is (string? (:body response))))))
