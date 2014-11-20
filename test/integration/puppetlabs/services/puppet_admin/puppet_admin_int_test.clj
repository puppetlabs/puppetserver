(ns puppetlabs.services.puppet-admin.puppet-admin-int-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
    [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration admin-api-access-control-test
  (testing "access denied when cert not on whitelist"
    (bootstrap/with-puppetserver-running app
      {:puppet-admin {:client-whitelist ["notlocalhost"]}}
      (let [response (http-client/delete
                       "https://localhost:8140/puppet-admin-api/v1/environment-cache"
                       ssl-request-options)]
        (is (= 401 (:status response))
            (ks/pprint-to-string response))))))
