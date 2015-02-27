(ns puppetlabs.enterprise.services.scheduler.scheduler-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.services.scheduler.scheduler-core :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :refer :all]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest wrap-with-error-logging-test
  (testing "when a job throws an exception, it is logged and re-thrown"
    (let [f #(throw (Exception. "bummer"))]
      (with-test-logging
        (is (thrown-with-msg? Exception
                              #"bummer"
                              ((wrap-with-error-logging f))))
        (is (logged? #"scheduled job threw error" :error))))))
