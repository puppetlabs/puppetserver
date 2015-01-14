(ns puppetlabs.services.master.master-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.master.master-core :refer :all]
            [ring.mock.request :as mock]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-master-routes
  (let [handler     (fn ([req] {:request req}))
        app         (build-ring-handler handler)
        request     (fn r ([path] (r :get path))
                          ([method path] (app (mock/request method path))))]
    (is (nil? (request "/v2.0/foo")))
    (is (= 200 (:status (request "/v2.0/environments"))))
    (is (nil? (request "/foo")))
    (is (nil? (request "/foo/bar")))
    (doseq [[method paths]
            {:get ["catalog"
                   "node"
                   "facts"
                   "file_content"
                   "file_metadatas"
                   "file_bucket_file"
                   "resource_type"
                   "resource_types"
                   "facts_search"]
             :post ["catalog"]
             :put ["file_bucket_file"
                   "report"]
             :head ["file_bucket_file"]}
            path paths]
      (let [resp (request method (str "/foo/" path "/bar"))]
        (is (= 200 (:status resp))
            (str "Did not get 200 for method: "
                 method
                 ", path: "
                 path))))))

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
