(ns puppetlabs.services.file-metadata.file-metadata-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/file_serving/file_metadata/file_metadata_int_test")

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
                (fs/file test-resources-dir "puppet.conf")))

(def ca-cert
  (bootstrap/pem-file "certs" "ca.pem"))

(def localhost-cert
  (bootstrap/pem-file "certs" "localhost.pem"))

(def localhost-key
  (bootstrap/pem-file "private_keys" "localhost.pem"))

(defn write-fileserver-conf
  [contents]
  (let [fileserver-conf (fs/file bootstrap/master-conf-dir "fileserver.conf")]
    (fs/mkdirs (fs/parent fileserver-conf))
    (println (str "writing conf to " fileserver-conf))
    (spit fileserver-conf contents)))

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

(def file-metadata-request-options
  (merge
    ssl-request-options
    {:headers {"Accept" "pson"}
     }))

(defn service-context
  [app service-id]
  (-> (tk-app/app-context app)
      deref
      service-id))

(defn wait-for-jrubies
  [app num-jrubies]
  (let [pool-context (-> (service-context app :JRubyPuppetService)
                         :pool-context)]
    (while (< (count (jruby-core/pool->vec pool-context))
              num-jrubies)
      (Thread/sleep 100))))

(defn file-metadata
  [file]
  (-> (http-client/get
        (format "https://localhost:8140/production/file_metadata/test/%s" file)
        file-metadata-request-options)
      :body
      slurp
      (json/parse-string true)))

(defn cheksum-match?
  [metadata expected]
  (= (get-in metadata [:data :checksum :value]) expected))

(deftest ^:integration file-metadata-integration-test
  (testing "get existing file metadata"
    (fs/copy-dir (str test-resources-dir "/test") bootstrap/master-conf-dir)
    (write-fileserver-conf (str "[test]\npath " bootstrap/master-conf-dir "/test\nallow *\n"))
    (bootstrap/with-puppetserver-running app {:jruby-puppet
                                              {:max-active-instances 1}}
                                         ;; if we start making requests before we know that all of the
                                         ;; jruby instances are ready, we won't be able to predict which
                                         ;; instance is handling our request, so we need to wait for them.
                                         (wait-for-jrubies app 1)
                                         ;; Now we grab a file-metadata from the jruby instance.
                                         (let [metadata (file-metadata "file.txt")]
                                           (is (cheksum-match? metadata "{md5}88e3322439accb57e30092301b1a98dd"))
                                         ))))