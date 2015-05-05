(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils])
  (:import (org.eclipse.jgit.api Git)
           (org.eclipse.jgit.lib PersonIdent RepositoryBuilder)))

(use-fixtures :once schema-test/validate-schemas)

(deftest initialize-repos-test
  (testing "A single repo"
    (let [data-dir (helpers/temp-file-name "data")
          repo-id "single-repo"
          working-dir (helpers/temp-file-name repo-id)
          git-dir (fs/file data-dir (str repo-id ".git"))]
      (initialize-repos! {:data-dir data-dir
                          :repos    {:single-repo {:working-dir working-dir}}})
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
                (Git/wrap repo) "test commit" (PersonIdent. "me" "me@you.com")))
          (is (jgit-utils/head-rev-id repo)))))))

(deftest reinitialize-repos-test
  (let [data-dir (fs/file (ks/temp-dir) "base")
        repos {:sub1 {:working-dir "sub1-dir"}
               :sub2 {:working-dir "sub2-dir"}
               :sub3 {:working-dir "sub3-dir/subsub3"}}
        config {:data-dir data-dir
                :repos    repos}]
    (testing "Multiple repos can be initialized"
      (initialize-repos! config))
    (testing "Content in repos not wiped out during reinitialization"
      (doseq [sub-path (map name (keys repos))]
        (let [file-to-check (fs/file data-dir sub-path (str sub-path ".txt"))]
          (ks/mkdirs! (.getParentFile file-to-check))
          (fs/touch file-to-check)))
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (let [file-to-check (fs/file data-dir sub-path (str sub-path ".txt"))]
          (is (fs/exists? file-to-check)
              (str "Expected file missing after repo reinitialization: "
                   file-to-check)))))))

