(ns puppetlabs.services.file-serving.utils.posix-utils-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.file-serving.utils.posix-utils :refer :all]))

(defn to-perm
  [mode]
  (java.nio.file.attribute.PosixFilePermissions/fromString mode))

(deftest test-posix-utils
  (testing "permissions -> mode"

    (is (= 0644 (permissions->mode (to-perm "rw-r--r--"))))
    (is (= 0755 (permissions->mode (to-perm "rwxr-xr-x"))))))
