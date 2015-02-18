(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core-test
  (:import (clojure.lang ExceptionInfo)
           (org.eclipse.jgit.api Git))
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.file-sync-test-utils
              :as jgit-client-test-helpers]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
              :refer :all]
            [puppetlabs.kitchensink.core :as ks]))

(use-fixtures :once schema-test/validate-schemas)

(defn validate-receive-pack-setting
  [repo-dir]
  (let [http-receive-pack (-> (fs/file repo-dir)
                              (Git/open)
                              (.getRepository)
                              (.getConfig)
                              (.getInt "http" "receivepack" -2))]
    (is (= 1 http-receive-pack)
        (str "Http receive pack was not set to 1 during initialization "
             "for repo-dir: "
             repo-dir))))

(defn validate-exception-info-for-initialize-repos!
  [message config]
  (is (thrown-with-msg?
        ExceptionInfo
        message
        (initialize-repos! config))))

(defn get-http-recievepack
  [repo]
  (-> repo
      (get-bare-repo)
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
        repos    [{:sub-path "sub1"}
                  {:sub-path "sub2"}
                  {:sub-path "sub3/subsub3"}]
        config   (jgit-client-test-helpers/file-sync-storage-config-payload
                   (.getPath base-dir)
                   repos)]
    (testing "Vector of repos can be initialized"
      (initialize-repos! config)
      (doseq [{:keys [sub-path]} repos]
        (validate-receive-pack-setting (fs/file base-dir sub-path))))
    (testing "Content in repos not wiped out during reinitialization"
      (doseq [{:keys [sub-path]} repos]
        (let [file-to-check (fs/file base-dir sub-path (str sub-path ".txt"))]
          (ks/mkdirs! (.getParentFile file-to-check))
          (fs/touch file-to-check)))
      (initialize-repos! config)
      (doseq [{:keys [sub-path]} repos]
        (let [file-to-check (fs/file base-dir sub-path (str sub-path ".txt"))]
          (is (fs/exists? file-to-check)
            (str "Expected file missing after repo reinitialization: "
                 file-to-check)))))
    (testing "Http receive pack for repos restored to 1 after reinitialization"
      (doseq [{:keys [sub-path]} repos]
        (fs/delete (fs/file base-dir sub-path "config")))
      (initialize-repos! config)
      (doseq [{:keys [sub-path]} repos]
        (validate-receive-pack-setting (fs/file base-dir sub-path))))
    (testing "ExceptionInfo thrown for missing base-path in config"
      (validate-exception-info-for-initialize-repos!
        #":base-path missing-required-key"
        (dissoc config :base-path)))
    (testing "ExceptionInfo thrown for missing repos in config"
      (validate-exception-info-for-initialize-repos!
        #":repos missing-required-key"
        (dissoc config :repos)))
    (testing "ExceptionInfo thrown for missing repos sub-path in config"
      (validate-exception-info-for-initialize-repos!
        #":sub-path missing-required-key"
        (assoc config :repos [{}])))))