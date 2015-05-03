(ns puppetlabs.services.master.master-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.master.master-core :refer :all]
            [ring.mock.request :as mock]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-master-routes
  (let [handler     (fn ([req] {:request req}))
        app         (build-ring-handler handler "1.2.3")
        request     (fn r ([path] (r :get path))
                          ([method path] (app (mock/request method path))))]
    (is (= 200 (:status (request "/v3/environments"))))
    (is (= 404 (:status (request "/foo"))))
    (is (= 404 (:status (request "/foo/bar"))))
    (doseq [[method paths]
            {:get ["catalog"
                   "node"
                   "facts"
                   "file_content"
                   "file_metadatas"
                   "file_metadata"
                   "file_bucket_file"
                   "resource_type"
                   "resource_types"
                   "facts_search"]
             :post ["catalog"]
             :put ["file_bucket_file"
                   "report"]
             :head ["file_bucket_file"]}
            path paths]
      (let [resp (request method (str "/v3/" path "/bar"))]
        (is (= 200 (:status resp))
            (str "Did not get 200 for method: "
                 method
                 ", path: "
                 path))))))

(deftest file-bucket-file-content-type-test
  (testing (str "The 'Content-Type' header on incoming /file_bucket_file requests "
                "is not overwritten, and simply passed through unmodified.")
    (let [handler     (fn ([req] {:request req}))
          app         (build-ring-handler handler "1.2.3")
          resp        (app {:request-method :put
                            :content-type   "application/octet-stream"
                            :uri            "/v3/file_bucket_file/bar"})]
      (is (= "application/octet-stream"
             (get-in resp [:request :content-type])))

      (testing "Even if the client sends something insane, "
               "just pass it through and let the puppet code handle it."
        (let [resp (app {:request-method :put
                         :content-type   "something-crazy/for-content-type"
                         :uri            "/v3/file_bucket_file/bar"})]
          (is (= "something-crazy/for-content-type"
                 (get-in resp [:request :content-type]))))))))

(defn assert-failure-msg
  "Assert the message thrown by validate-memory-requirements! matches re"
  [re behavior-msg]
  (testing (str "the error " behavior-msg)
    (is (thrown-with-msg? Error re (validate-memory-requirements!)))))

(deftest validate-memory-requirements!-test
  (testing "when /proc/meminfo does not exist"
    (with-redefs [meminfo-content (constantly nil)
                  max-heap-size 2097152]
      (is (nil? (validate-memory-requirements!))
          "nil when /proc/meminfo does not exist")))
  (testing "when ram is > 1.1 times JVM max heap"
    (with-redefs [meminfo-content #(str "MemTotal:        3878212 kB\n")
                  max-heap-size 2097152]
      (is (nil? (validate-memory-requirements!))
          "nil when ram is > 1.1 times JVM max heap")))
  (testing "when ram is < 1.1 times JVM max heap"
    (with-redefs [meminfo-content #(str "MemTotal:        1878212 kB\n")
                  max-heap-size 2097152]
      (assert-failure-msg #"RAM (.*) JVM heap"
                          "mentions RAM and JVM Heap size")
      (assert-failure-msg #"JAVA_ARGS"
                          "suggests the user configure JAVA_ARGS")
      (assert-failure-msg #"computed as 1.1 *"
                          "informs the user how required memory is calculated")
      (assert-failure-msg #"/etc/sysconfig/puppetserver"
                          "points the user to the EL config location")
      (assert-failure-msg #"/etc/default/puppetserver"
                          "points the user to the debian config location"))))
