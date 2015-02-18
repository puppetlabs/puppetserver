(ns puppetlabs.enterprise.jgit-client-test
  (:import (org.eclipse.jgit.api Git))
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.jgit-client :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.kitchensink.core :as ks]))

(deftest test-head-rev-id
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir  (ks/temp-dir)
          git       (helpers/init-repo! repo-dir)]

      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id repo-dir))))

      (helpers/write-test-file (str repo-dir "/test.txt"))
      (let [commit  (add-and-commit git "a test commit" helpers/author)
            id      (commit-id commit)]
        (is (= (head-rev-id repo-dir) id))))))
