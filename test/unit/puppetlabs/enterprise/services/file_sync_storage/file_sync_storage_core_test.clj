(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core-test
  (:import (clojure.lang ExceptionInfo)
           (org.eclipse.jgit.api Git))
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.file-sync-test-utils
             :as helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
             :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.jgit-client :as client]))

(use-fixtures :once schema-test/validate-schemas)

(defn get-http-recievepack
  [repo]
  (-> repo
      (client/get-repository-from-git-dir)
      (.getConfig)
      (.getInt "http" "receivepack" (Integer/MIN_VALUE))))

(deftest initialize-repo!-test
  (testing "The repo's 'http.receivepack' setting should be 0 when the
           'allow-anonymous-push?' parameter is false."
    (let [repo (ks/temp-dir)]
      (initialize-repo! repo false)
      (let [receivepack (get-http-recievepack repo)]
        (is (= 0 receivepack)))))

  (testing "The repo's 'http.receivepack' setting should be 1 when the
           'allow-anonymous-push?' parameter is true."
    (let [repo (ks/temp-dir)]
    (initialize-repo! repo true)
    (let [receivepack (get-http-recievepack repo)]
      (is (= 1 receivepack))))))

(deftest initialize-repos!-test
  (let [base-dir (fs/file (ks/temp-dir) "base")
        repos {:sub1 {:working-dir "sub1-dir"}
               :sub2 {:working-dir "sub2-dir"}
               :sub3 {:working-dir "sub3-dir/subsub3"}}
        config   (helpers/file-sync-storage-config-payload
                   (.getPath base-dir)
                   repos)]
    (testing "Vector of repos can be initialized"
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (is (= 1 (get-http-recievepack (fs/file base-dir sub-path)))
            (str "Repo at " sub-path "has incorrect http-recievepack setting"))))
    (testing "Content in repos not wiped out during reinitialization"
      (doseq [sub-path (map name (keys repos))]
        (let [file-to-check (fs/file base-dir sub-path (str sub-path ".txt"))]
          (ks/mkdirs! (.getParentFile file-to-check))
          (fs/touch file-to-check)))
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (let [file-to-check (fs/file base-dir sub-path (str sub-path ".txt"))]
          (is (fs/exists? file-to-check)
            (str "Expected file missing after repo reinitialization: "
                 file-to-check)))))
    (testing "Http receive pack for repos restored to 1 after reinitialization"
      (doseq [sub-path (map name (keys repos))]
        (fs/delete (fs/file base-dir sub-path "config")))
      (initialize-repos! config)
      (doseq [sub-path (map name (keys repos))]
        (is (= 1 (get-http-recievepack (fs/file base-dir sub-path)))
            (str "Repo at " sub-path "has incorrect http-recievepack setting"))))))

