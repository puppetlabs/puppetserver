(ns puppetlabs.enterprise.jgit-utils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.enterprise.jgit-utils :refer :all]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.kitchensink.core :as ks]
            [schema.test :as schema-test]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [me.raynes.fs :as fs])
  (:import (org.eclipse.jgit.api Git)))

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
        (let [commit (add-and-commit git "a test commit" helpers/test-identity)
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
        (let [commit (add-and-commit git "a test commit" helpers/test-identity)
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
              commit (add-and-commit local-repo "a test commit" helpers/test-identity)
              commit-id (commit-id commit)]
          (push local-repo (str repo-dir))

          (is (= (head-rev-id-from-git-dir repo-dir) commit-id)))))))

(deftest test-remove-submodules-configuration
  (let [repo-dir (helpers/temp-dir-as-string)
        local-repo-dir (helpers/temp-dir-as-string)
        submodule-dir (helpers/temp-dir-as-string)]

    (helpers/init-bare-repo! (fs/file repo-dir))
    (helpers/init-bare-repo! (fs/file submodule-dir))
    (let [repo (jgit-utils/get-repository repo-dir local-repo-dir)]
      (jgit-utils/submodule-add!
        (Git. repo)
        submodule-dir submodule-dir)
      (let [git-config (.getConfig repo)
            gitmodules (jgit-utils/submodules-config repo)]
        (.load gitmodules)
        (testing "git config and .gitmodules contain submodule"
          (is (= 1 (count (.getSubsections git-config "submodule"))))
          (is (= 1 (count (.getSubsections gitmodules "submodule"))))
          (is (= submodule-dir (.getString git-config "submodule" submodule-dir "url")))
          (is (= submodule-dir (.getString gitmodules "submodule" submodule-dir "url"))))

        (testing (str "remove-submodule-configuration! successfully removes "
                      "submodule configuration")
          (jgit-utils/remove-submodule-configuration! repo submodule-dir)
          (.load gitmodules)
          (.load git-config)

          (is (empty? (.getSubsections git-config "submodule")))
          (is (empty? (.getSubsections gitmodules "submodule"))))))))

(deftest test-remove-submodule
  (let [repo-dir (helpers/temp-dir-as-string)
        local-repo-dir (helpers/temp-dir-as-string)
        submodule-dir (helpers/temp-dir-as-string)
        submodule-path "test-submodule"]

    (helpers/init-bare-repo! (fs/file repo-dir))
    (helpers/init-bare-repo! (fs/file submodule-dir))
    (let [repo (jgit-utils/get-repository repo-dir local-repo-dir)
          git (Git. repo)]
      (jgit-utils/submodule-add! git submodule-path submodule-dir)
      (jgit-utils/add! git ".")
      (jgit-utils/commit git "test commit" {:name "test" :email "test"})
      (testing "submodule successfully added to repo"
        (is (= [submodule-path] (jgit-utils/get-submodules repo))))
      (jgit-utils/remove-submodule! repo submodule-path)
      (testing "submodule successfully removed from repo"
        (is (= 0 (count (jgit-utils/get-submodules repo))))))))

(deftest test-clone
  (testing "When clone fails, it does not leave a git bogus repository behind"
    (testing "Normal clone (not bare)"
      (let [repo-dir (fs/temp-dir "test-clone")]
        (is (thrown?
              Exception
              (clone "http://invalid" repo-dir)))
        (is (not (fs/exists? (fs/file repo-dir ".git"))))
        (is (fs/exists? repo-dir))))
    (testing "Bare repo"
      (testing "Existing repo dir"
        (let [repo-dir (fs/temp-dir "test-clone.git")]
          (is (fs/exists? repo-dir))
          (is (thrown?
                Exception
                (clone "http://invalid" repo-dir true)))
          (testing "Exsting directory should not be deleted"
            (is (fs/exists? repo-dir)))
          (testing "But it should be empty"
            (is (empty? (fs/list-dir repo-dir))))))
      (testing "Repo dir doesn't yet exist"
        (let [repo-dir (helpers/temp-file-name "test-clone.git")]
          (is (not (fs/exists? repo-dir)))
          (is (thrown?
                Exception
                (clone "http://invalid" repo-dir true)))
          (testing "Directory which did not exist should not be created"
            (is (not (fs/exists? repo-dir)))))))))

(deftest test-submodule-add
  (testing "When submodule-add! fails, it does not leave a git bogus repository behind"
    (let [repo-dir (fs/temp-dir "test-submodule-add")
          submodule-name "my-submodule"]
      (helpers/init-repo! repo-dir)
      (is (thrown?
            Exception
            (submodule-add! (Git/open repo-dir) submodule-name "http://invalid")))
      (is (not (fs/exists? (fs/file repo-dir submodule-name)))))))
