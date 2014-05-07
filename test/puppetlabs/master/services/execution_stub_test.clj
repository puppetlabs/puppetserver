(ns puppetlabs.master.services.execution-stub-test
  (:import (com.puppetlabs.master ExecutionStubImpl))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]))

(defn execute-ls
  [dir]
  (->
    (str "ls " dir)
    (ExecutionStubImpl/executeCommand)
    (.trim)))

(deftest test-it
  (let [temp-dir (ks/temp-dir)]
    (is (= "" (execute-ls temp-dir)))
    (ExecutionStubImpl/executeCommand (str "touch " temp-dir "/foo"))
    (is (= "foo" (execute-ls temp-dir)))))
