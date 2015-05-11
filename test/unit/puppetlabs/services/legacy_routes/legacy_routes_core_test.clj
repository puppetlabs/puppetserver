(ns puppetlabs.services.legacy-routes.legacy-routes-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.legacy-routes.legacy-routes-core :refer :all]
            [puppetlabs.services.master.master-core :as master-core]
            [ring.mock.request :as mock]
            [ring.util.codec :as ring-codec]
            [clojure.string :as str]))

(use-fixtures :once schema-test/validate-schemas)

(def master-mount "/puppet")
(def ca-mount "/puppet-ca")
(def master-api-version master-core/puppet-API-versions)
(def ca-api-version master-core/puppet-ca-API-versions)
(def accept-header [:headers "accept"])
(def content-type-header [:headers "content-type"])

(def accept-header-common-examples
  {"raw" "binary"
   "foo" "foo"
   "foo,bar" "foo, bar"
   "raw, foo" "binary, foo"
   "foo, raw" "foo, binary"
   "foo, raw, bar" "foo, binary, bar"})

(def accept-header-raw-examples
  (assoc accept-header-common-examples
    "s, pson, raw" "s, pson, binary"))

(def accept-header-s-pson-examples
  (assoc accept-header-common-examples
    "s, pson, raw" "binary"
    "s, pson, foo" "binary, foo"))

;
(def accept-header-test-data
  "Nested map of request data.  The map keys represent URI paths, the request
  method, the example input, and the expected output.

  {path {method {example expected}}}"
  {
   "/" {:head {"raw" "raw"}
        :post {"raw" "raw"}
        :get {"raw" "raw"
              "foo,bar" "foo,bar"}}
   "/production/catalog/anode" {:get accept-header-raw-examples
                                :post accept-header-raw-examples}
   "/production/file_content/something" {:get accept-header-raw-examples}
   "/production/file_bucket_file/something" {:get accept-header-s-pson-examples
                                             :head accept-header-s-pson-examples
                                             :put accept-header-s-pson-examples}
   })

(defn assertion-wrapper
  "ring middleware that asserts by passing the request as the first argument
  to assertion"
  [handler assertion]
  (fn [req]
    (assertion req)
    (handler req)))

(defn validating-app
  "Return a ring app with the results of build-ring-handler wrapped around an
  assertion-wrapper middleware as the master-handler.  The identity function is
  the ca-handler."
  [assertion]
  (let [handler (assertion-wrapper identity assertion)]
    (build-ring-handler
      handler master-mount master-api-version
      identity ca-mount ca-api-version)))

(defn test-munged-request
  "Validate a request by threading it through the legacy-routes request handler
  into an assertion-wrapper ring middleware."
  [request assertion]
  ((validating-app assertion) request))

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

(deftest test-v3-header-munging
  (testing "(SERVER-548) Header munging"
    (testing "Accept: raw is munged to Accept: binary for all paths"
      (doseq [[path methods] accept-header-test-data
              [method examples] methods
              [example expected] examples
              :let [mockreq (mock/request method path)
                    request (assoc-in mockreq accept-header example)]]
        (test-munged-request request
          (fn [munged-request] ; ring middleware
            (let [actual (get-in munged-request accept-header)
                  msg (str/join " "
                        ["data:" path method example "=>" expected])]
              (is (= expected actual) msg))))))
    (testing (str "Content-Type: application/octet-stream "
               "is added to file_bucket_file put requests")
      (let [request (mock/request :put "/production/file_bucket_file/foo")]
        (test-munged-request request
          (fn [munged-request] ; ring middleware
            (let [actual (get-in munged-request content-type-header)]
              (is (= "application/octet-stream" actual)))))
        (test-munged-request request
          (fn [munged-request] ; ring middleware
            (let [actual (get-in munged-request content-type-header)]
              (is (= "application/octet-stream" actual)))))))))
