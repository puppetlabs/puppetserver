(ns puppetlabs.services.jruby.request-handler-test
  (:import (java.io ByteArrayInputStream)
           (java.security MessageDigest)
           (org.apache.commons.io IOUtils))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]
            [schema.test :as schema-test]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.puppetserver.testutils :as testutils :refer
             [ca-cert localhost-cert localhost-key ssl-request-options]]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/request_handler_test")

(use-fixtures :once
              schema-test/validate-schemas
              (testutils/with-puppet-conf (fs/file test-resources-dir
                                                         "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration file-bucket-test
  (testing "that a file bucket upload with *binary*, non-UTF-8, content is
            successful (SERVER-269)"
    (let [bucket-dir (str bootstrap/master-var-dir "/bucket")]
      (fs/delete-dir bucket-dir)
      (bootstrap/with-puppetserver-running
        app
        {:jruby-puppet {:gem-path [(ks/absolute-path jruby-testutils/gem-path)]}}
       (try
         (let [raw-byte-arr            (byte-array [(byte -128)
                                                    (byte -127)
                                                    (byte -126)])
               digested                (.digest
                                         (MessageDigest/getInstance "MD5")
                                         raw-byte-arr)
               expected-md5            (.toString (BigInteger. 1 digested) 16)
               expected-bucket-file    (string/join
                                         "/"
                                         [bucket-dir
                                          (string/join "/"
                                                       (subs expected-md5 0 8))
                                          expected-md5
                                          "contents"])
               options                 (merge ssl-request-options
                                              {:body (ByteArrayInputStream.
                                                       raw-byte-arr)
                                               :headers {"accept" "binary"
                                                         "content-type" "application/octet-stream"}})
               response (http-client/put (str "https://localhost:8140/"
                                              "puppet/v3/file_bucket_file/md5/"
                                              expected-md5
                                              "?environment=production")
                                         options)]
           (is (= 200 (:status response)) "Bucket PUT request failed")
           (is (fs/exists? expected-bucket-file)
               "Bucket file not stored at expected location")
           (is (= (seq raw-byte-arr)
                  (if (fs/exists? expected-bucket-file)
                    (-> expected-bucket-file
                        (io/input-stream)
                        (IOUtils/toByteArray)
                        (seq))))
               "Did not find expected content in bucket file"))
         (finally
           (fs/delete-dir bucket-dir)))))))
