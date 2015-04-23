(ns puppetlabs.enterprise.jgit-client-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.jgit-client :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.kitchensink.core :as ks]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-head-rev-id
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir (ks/temp-dir)
          git (helpers/init-repo! repo-dir)
          repo (.getRepository git)]

      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id repo))))

      (testing "It should return the correct commit id for a repo with commits"
        (helpers/write-test-file! (str repo-dir "/test.txt"))
        (let [commit (add-and-commit git "a test commit" helpers/author)
              id (commit-id commit)]
          (is (= (head-rev-id repo) id)))))))

(deftest test-head-rev-id-with-working-tree
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir (ks/temp-dir)
          git (helpers/init-repo! repo-dir)]

      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id-from-working-tree repo-dir))))

      (testing "It should return the correct commit id for a repo with commits"
        (helpers/write-test-file! (str repo-dir "/test.txt"))
        (let [commit (add-and-commit git "a test commit" helpers/author)
              id (commit-id commit)]
          (is (= (head-rev-id-from-working-tree repo-dir) id)))))))

(deftest test-head-rev-id-with-git-dir
  (testing "Getting the latest commit on the current branch of a repo"
    (let [repo-dir (ks/temp-dir)
          local-repo-dir (ks/temp-dir)]

      (helpers/init-bare-repo! repo-dir)
      (testing "It should return `nil` for a repo with no commits"
        (is (= nil (head-rev-id-from-git-dir repo-dir))))

      (testing "It should return the correct commit id for a repo with commits"
        (helpers/write-test-file! (str local-repo-dir "/test.txt"))
        (let [local-repo (helpers/init-repo! local-repo-dir)
              commit (add-and-commit local-repo "a test commit" helpers/author)
              commit-id (commit-id commit)]
          (push local-repo (str repo-dir))

          (is (= (head-rev-id-from-git-dir repo-dir) commit-id)))))))
