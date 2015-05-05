(ns puppetlabs.services.master.master-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [cheshire.core :as json]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/master_int_test/")

(defn test-file
  [filename]
  (str test-resources-dir filename))

(def ssl-options
  {:ssl-ca-cert (test-file "ca-cert.pem")
   :ssl-key (test-file "localhost-privkey.pem")
   :ssl-cert (test-file "localhost-cert.pem")})

(def http-client-options
  (merge ssl-options
         {:as :text}))

(deftest status-callback-test
  (bootstrap/with-puppetserver-running app
    {:webserver (merge ssl-options
                       {:ssl-host "localhost"
                        :ssl-port 8140})}
    (testing "Status service callback is registered properly"
      (let [resp (http-client/get "https://localhost:8140/status/v1/services"
                                  http-client-options)
            status (-> resp :body json/parse-string
                       (get "puppet-server")
                       (get "status"))]
        (is (= 200 (:status resp)))
        (is (= {"bar" "bar"
                "foo" "foo"}
               status))))))
