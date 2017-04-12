(ns puppetlabs.services.legacy-routes.legacy-routes-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.legacy-routes.legacy-routes-core :refer :all]
            [puppetlabs.services.ca.certificate-authority-core :as ca-core]
            [puppetlabs.services.master.master-core :as master-core]

            [ring.mock.request :as mock]
            [ring.util.codec :as ring-codec]))
(use-fixtures :once schema-test/validate-schemas)

(def master-mount "/puppet")
(def ca-mount "/puppet-ca")
(def master-api-version master-core/puppet-API-version)
(def ca-api-version ca-core/puppet-ca-API-version)
(def accept-header [:headers "accept"])
(def content-type-request-header [:headers "content-type"])

(def master-info
  {:mount master-mount
   :handler identity
   :api-version master-api-version})

(def ca-info
  {:mount ca-mount
   :handler identity
   :api-version ca-api-version})

(def accept-header-common-examples
  {"raw" "binary"
   "foo" "foo"
   "foo,bar" "foo, bar"
   "raw, foo" "binary, foo"
   "foo, raw" "foo, binary"
   "foo, raw, bar" "foo, binary, bar"})

(def accept-header-raw-examples
  (assoc accept-header-common-examples
    "s, pson, raw" "binary, pson"))

(def accept-header-s-pson-examples
  (assoc accept-header-common-examples
    "s, pson, raw" "binary, pson"
    "pson, s, raw, foo" "pson, binary, foo"
    "s, pson, foo" "binary, pson, foo"))

(def accept-header-test-data
  "Nested map of request data.  The map keys represent URI paths, the request
  method, the example input, and the expected output.

  {path {method {example expected}}}"
  {
   "/production/node/anode" { :get {"raw" "binary"
                                    "foo,bar" "foo, bar"}}
   "/production/catalog/anode" {:get accept-header-raw-examples
                                :post accept-header-raw-examples}
   "/production/file_content/something" {:get accept-header-raw-examples}
   "/production/file_bucket_file/something" {:get accept-header-s-pson-examples
                                             :head accept-header-s-pson-examples
                                             :put accept-header-s-pson-examples}
   })

(defn build-app
  "Build a ring app with handler as the handler and the identity function as
  the ca-handler"
  [handler]
  (build-ring-handler (assoc master-info :handler handler) ca-info))

(defn munged-request
  "Return the request that would be sent to the master-handler after processing
  by the legacy-routes handler."
  [request]
  (let [mem (atom {:tested false})
        tapped-app (build-app #(swap! mem assoc :request %))]
    (tapped-app request)
    (:request @mem)))

(deftest test-legacy-routes
  (let [app     (build-ring-handler master-info ca-info)
        request (fn r ([path] (r :get path))
                      ([method path]
                       (app (mock/request method path))))
        environment "production"
        route-val "foo"]
    (is (= 200 (:status (request :get "/v2.0/environments"))))
    (is (nil? (request :get "/does/not/exist")))

    (testing "Legacy master routes"
      (doseq [[method paths] {:get ["node"
                                    "file_content"
                                    "file_metadatas"
                                    "file_metadata"
                                    "file_bucket_file"
                                    "catalog"]
                              :put ["report"]
                              :head ["file_bucket_file"]
                              :post ["catalog"]}
              path paths]
        (let [resp (request method (str "/" environment "/" path "/" route-val))]
          (is (= 200 (:status resp)))
          (is (= (str master-mount "/" master-api-version "/" path "/" route-val)
                 (:uri resp)))
          (is (.contains (:query-string resp) (ring-codec/form-encode
                                                {:environment environment}))))))

    (testing "Legacy ca routes"
      (doseq [path ["certificate_status"
                    "certificate_statuses"
                    "certificate"
                    "certificate_revocation_list"
                    "certificate_request"]]
        (let [resp (request (str "/" environment "/" path "/" route-val))]
          (is (= 200 (:status resp)))
          (is (= (str ca-mount "/" ca-api-version "/" path "/" route-val)
                 (:uri resp)))
          (is (= (ring-codec/form-encode {:environment environment})
                 (:query-string resp))))))

    (testing "file_bucket_file GET responses are Content-Type: text/plain"
      (let [resp (request :get (str "/production/file_bucket_file/" route-val))]
        (is (= 200 (:status resp)))
        (is (= "text/plain" (get-in resp [:headers "Content-Type"])))))

    (testing "file_metadata route contains proper query string"
      (doseq [endpoint ["file_metadata" "file_metadatas"]]
        (let [resp (request :get (str "/production/" endpoint "/some_file"))]
          (is (.contains (:query-string resp) (ring-codec/form-encode
                                               {"source_permissions" "use"}))
              (str
               "The " endpoint " endpoint should contain the query parameter "
               "source_permissions=use")))))

    (testing (str
               "If a source_permissions key is provided, use it instead of "
               "setting the default")
      (let [source-permissions-str "source_permissions=foo"
            resp (request :get
                   (str "/production/file_metadata/athing?"
                     source-permissions-str))]
        (is (.contains (:query-string resp) source-permissions-str))
        (is (not (.contains (:query-string resp) "source_permissions=use"))
          "The default source_permissions value of `use` was found.")))))

(deftest test-v3-header-munging
  (testing "(SERVER-548) Header munging"
    (testing "Accept: header is translated for use with the master-handler"
      (doseq [[path methods] accept-header-test-data
              [method examples] methods
              [example expected] examples
              :let [mockreq (mock/request method path)
                    request (assoc-in mockreq accept-header example)
                    subject (get-in (munged-request request) accept-header)]]
        (is (= expected subject)
            (str "Request to " path " failed for method " method
                 " expecting header 'accepts: " expected "' got 'accepts: "
                 subject "'"))))))

(deftest test-v3-file-bucket-file-put
  (testing (str "file_bucket_file put requests have application/octet-stream")
    (let [request (mock/request :put "/production/file_bucket_file/foo")
          munged-req (munged-request request)
          content-type (get-in munged-req [:headers "content-type"])]
      (is (= "application/octet-stream" content-type)))))
