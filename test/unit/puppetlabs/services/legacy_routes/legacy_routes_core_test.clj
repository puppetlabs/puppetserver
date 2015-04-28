(ns puppetlabs.services.legacy-routes.legacy-routes-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.legacy-routes.legacy-routes-core :refer :all]
            [puppetlabs.services.master.master-core :as master-core]
            [ring.mock.request :as mock]
            [ring.util.codec :as ring-codec]))

(use-fixtures :once schema-test/validate-schemas)

(def master-mount "/puppet")
(def ca-mount "/puppet-ca")
(def master-api-version master-core/puppet-API-versions)
(def ca-api-version master-core/puppet-ca-API-versions)

(deftest test-legacy-routes
  (let [app     (build-ring-handler
                  identity master-mount master-api-version
                  identity ca-mount ca-api-version)
        request (fn r ([path] (r :get path))
                      ([method path]
                       (app (mock/request method path))))
        environment "production"
        route-val "foo"]
    (is (= 200 (:status (request :get "/v2.0/environments"))))
    (is (= 404 (:status (request :get "/does/not/exist"))))

    (testing "Legacy master routes"
      (doseq [[method paths] {:get ["node"
                                    "facts"
                                    "file_content"
                                    "file_metadatas"
                                    "file_metadata"
                                    "file_bucket_file"
                                    "catalog"
                                    "resource_type"
                                    "resource_types"
                                    "facts_search"]
                              :put ["report"]
                              :head ["file_bucket_file"]
                              :post ["catalog"]}
              path paths]
        (let [resp (request method (str "/" environment "/" path "/" route-val))]
          (is (= 200 (:status resp)))
          (is (= (str master-mount "/" master-api-version "/" path "/" route-val)
                 (:uri resp)))
          (is (= (ring-codec/form-encode {:environment environment})
                 (:query-string resp))))))

    (testing "Legacy ca routes"
      (doseq [path ["certificate_status"
                    "certificate_statuses"
                    "certificate"
                    "certificate_revocation_list"
                    "certificate_request"
                    "certificate_requests"]]
        (let [resp (request (str "/" environment "/" path "/" route-val))]
          (is (= 200 (:status resp)))
          (is (= (str ca-mount "/" ca-api-version "/" path "/" route-val)))
          (is (= (ring-codec/form-encode {:environment environment}))))))))
