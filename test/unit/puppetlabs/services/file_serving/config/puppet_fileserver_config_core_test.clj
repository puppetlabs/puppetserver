(ns puppetlabs.services.file-serving.config.puppet-fileserver-config-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.file-serving.config.puppet-fileserver-config-core :refer :all]))

(def fileserver-conf "./dev-resources/puppetlabs/services/file_serving/config/master/conf/fileserver.conf")

(deftest test-puppet-fileserver-config
  (let [expected {:files1 {:path "/etc/puppet/files1" :acl [:allow "127.0.0.1/32" :allow "192.168.10.0/24" :allow "this-certname-only.domain.com"]}
                  :files2 {:path "/etc/puppet/files2" :acl [:allow "127.0.0.1/32" :deny "forbidden-certname.domain.com"]}}]

    (testing "parsing fileserver.conf"
      (let [actual (fileserver-parse fileserver-conf)]
        (is (= expected actual)))))

    (testing "finding an existing mount"
      (let [mounts (fileserver-parse fileserver-conf)]
        (is (= [{:path "/etc/puppet/files1" :acl [:allow "127.0.0.1/32" :allow "192.168.10.0/24" :allow "this-certname-only.domain.com"]} "path/to/file"] (find-mount mounts "files1/path/to/file")))))

    (testing "finding an inexisting mount"
      (let [mounts (fileserver-parse fileserver-conf)]
        (is (thrown? Exception (find-mount mounts "unknown/path/to/file"))))))
