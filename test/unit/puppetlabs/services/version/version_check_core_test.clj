(ns puppetlabs.services.version.version-check-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.services.version.version-check-core :refer :all]
            [schema.test :as schema-test]
            [cheshire.core :as json]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-get-coords
  (testing "group-id should use the default if not specified"
    (is (= {:group-id    "puppetlabs.packages"
            :artifact-id "foo"}
           (get-coords "foo"))))
  (testing "should use group-id if specified"
    (is (= {:group-id    "foo.foo"
            :artifact-id "foo"}
           (get-coords {:group-id "foo.foo"
                        :artifact-id "foo"})))))

(defn update-available-app
  [req]
  {:status 200
   :body (json/generate-string {:newer true
                                :link "http://foo.com"
                                :message "Howdy!"
                                :product "foo"
                                :version "9000.0.0"})})

(defn server-error-app
  [req]
  {:status 500
   :body "aaaaaaaaaaaaaaaaaaaaaaaaaa"})

(deftest test-check-for-updates
  (with-test-logging
    (jetty9/with-test-webserver update-available-app port
      (check-for-updates "foo" (format "http://localhost:%s" port))
      (is (logged? #"Newer version 9000.0.0 is available!" :info))))
  (with-test-logging
    (jetty9/with-test-webserver server-error-app port
      (check-for-updates "foo" (format "http://localhost:%s" port))
      (is (logged? #"Could not retrieve update information" :debug)))))




