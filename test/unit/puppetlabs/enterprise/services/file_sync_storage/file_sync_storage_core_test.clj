(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :refer :all]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils])
  (:import (org.eclipse.jgit.api Git)))

(use-fixtures :once schema-test/validate-schemas)

(deftest initialize-repos-test
  (testing "A single repo"
    (let [data-dir (helpers/temp-file-name "data")
          repo-id "single-repo"
          working-dir (helpers/temp-file-name repo-id)
          git-dir (fs/file data-dir (str repo-id ".git"))]
      (initialize-repos! {:repos    {:single-repo {:working-dir working-dir}}}
                         (str data-dir))
      (testing "The data dir is created"
        (is (fs/exists? data-dir)))
      (testing "The working dir and git dirs are created"
        (is (fs/exists? working-dir))
        (is (fs/exists? git-dir)))
      (testing "The git dir is initialized correctly"
        (is (not (nil? (jgit-utils/get-repository-from-git-dir git-dir))))
        (is (.isBare (jgit-utils/get-repository-from-git-dir git-dir)))
        (is (nil? (jgit-utils/head-rev-id-from-git-dir git-dir))))
      (testing "The working dir can be used as a git working tree"
        (spit (fs/file working-dir "test-file") "howdy")
        (let [repo (jgit-utils/get-repository git-dir working-dir)]
          (is (jgit-utils/add-and-commit
                (Git/wrap repo) "test commit" helpers/test-identity))
          (is (jgit-utils/head-rev-id repo)))))))

(defn commit!
  [& repos]
  (doseq [repo repos]
    (jgit-utils/add-and-commit
      (Git/wrap repo)
      "test commit"
      helpers/test-identity)))

(deftest reinitialize-repos-test
  (let [data-dir          (fs/temp-dir "data")
        repo1-id          "repo1"
        repo2-id          "repo2"
        repo1-working-dir (fs/temp-dir repo1-id)
        repo2-working-dir (fs/temp-dir repo2-id)
        config            {:repos    {:repo1 {:working-dir repo1-working-dir}
                                      :repo2 {:working-dir repo2-working-dir}}}]
    (testing "Multiple repos can be initialized"
      (initialize-repos! config (str data-dir)))
    (testing "Content in repos not wiped out during reinitialization"
      (let [repo1-git-dir (fs/file data-dir (str repo1-id ".git"))
            repo2-git-dir (fs/file data-dir (str repo2-id ".git"))
            repo1 (jgit-utils/get-repository repo1-git-dir repo1-working-dir)
            repo2 (jgit-utils/get-repository repo2-git-dir repo2-working-dir)]
        (spit (fs/file repo1-working-dir "test-file1") "foo")
        (spit (fs/file repo2-working-dir "test-file2") "bar")
        (commit! repo1 repo2)
        (let [head-rev-id1 (jgit-utils/head-rev-id repo1)
              head-rev-id2 (jgit-utils/head-rev-id repo2)]
          (initialize-repos! config (str data-dir))
          (testing "Git repos have same HEAD after reinitialization."
            (is (= head-rev-id1 (jgit-utils/head-rev-id repo1)))
            (is (= head-rev-id2 (jgit-utils/head-rev-id repo2))))
          (testing "Working dirs are unnaffected."
            (is (= ["test-file1"] (fs/list-dir repo1-working-dir)))
            (is (= "foo" (slurp (fs/file repo1-working-dir "test-file1"))))
            (is (= ["test-file2"] (fs/list-dir repo2-working-dir)))
            (is (= "bar" (slurp (fs/file repo2-working-dir "test-file2"))))))))))
