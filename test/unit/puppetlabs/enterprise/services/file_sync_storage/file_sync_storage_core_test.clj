(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.file-sync-test-utils :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :refer :all]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once schema-test/validate-schemas)

(deftest initialize-repos!-test
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

