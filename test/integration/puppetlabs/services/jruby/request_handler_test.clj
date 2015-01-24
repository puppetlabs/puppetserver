(ns puppetlabs.services.jruby.request-handler-test
  (:import (java.io ByteArrayInputStream)
           (java.security MessageDigest)
           (javax.xml.bind.annotation.adapters HexBinaryAdapter)
           (org.apache.commons.io IOUtils))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/request_handler_test")

(use-fixtures :once
              schema-test/validate-schemas
              (jruby-testutils/with-puppet-conf (fs/file test-resources-dir
                                                         "puppet.conf")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(def ca-cert
  (bootstrap/pem-file "certs" "ca.pem"))

(def localhost-cert
  (bootstrap/pem-file "certs" "localhost.pem"))

(def localhost-key
  (bootstrap/pem-file "private_keys" "localhost.pem"))

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration file-bucket-test
  (testing "that a file bucket upload with *binary*, non-UTF-8, content is
            successful (SERVER-269)"
    (let [bucket-dir (str bootstrap/master-var-dir "/bucket")]
      (fs/delete-dir bucket-dir)
      (bootstrap/with-puppetserver-running app {}
       (try
         (let [raw-byte-arr            (byte-array [(byte -128)
                                                    (byte -127)
                                                    (byte -126)])
               expected-md5            (-> (HexBinaryAdapter.)
                                           (.marshal (->
                                                       (MessageDigest/getInstance
                                                         "MD5")
                                                       (.digest raw-byte-arr)))
                                           (string/lower-case))
               expected-bucket-file    (string/join
                                         "/"
                                         [bucket-dir
                                          (string/join "/"
                                                       (subs expected-md5 0 8))
                                          expected-md5
                                          "contents"])
               ;; The 'text/plain' content-type mimics what a Puppet agent
               ;; sends to a master for a file-bucket PUT.  Ideally, this
               ;; should be "application/octet-stream" but including this at
               ;; present would result in a Puppet Ruby error - Puppet Client
               ;; sent a mime-type (application/octet-stream) that doesn't
               ;; correspond to a format we support.
               options                 (merge ssl-request-options
                                              {:body (ByteArrayInputStream.
                                                       raw-byte-arr)
                                               :headers {"accept"
                                                           "s, pson"
                                                         "content-type"
                                                           "text/plain"}})
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