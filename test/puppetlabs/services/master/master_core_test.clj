(ns puppetlabs.services.master.master-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.master.master-core :refer :all]
            [ring.mock.request :as mock]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.certificate-authority.core :as ca-utils]
            [puppetlabs.services.ca.testutils :as ca-test-utils]))

(use-fixtures :once schema-test/validate-schemas)

(def resources-dir "dev-resources/puppetlabs/services/master/master_core_test")

(deftest test-master-routes
  (let [handler     (fn ([req] {:request req}))
        app         (compojure-app handler)
        request     (fn r ([path] (r :get path))
                          ([method path] (app (mock/request method path))))]
    (is (nil? (request "/v2.0/foo")))
    (is (= 200 (:status (request "/v2.0/environments"))))
    (is (nil? (request "/foo")))
    (is (nil? (request "/foo/bar")))
    (doseq [[method paths]
            {:get ["catalog"
                   "node"
                   "facts"
                   "file_content"
                   "file_metadatas"
                   "file_metadata"
                   "file_bucket_file"
                   "resource_type"
                   "resource_types"]
             :post ["catalog"]
             :put ["file_bucket_file"
                   "report"]
             :head ["file_bucket_file"]}
            path paths]
      (let [resp (request method (str "/foo/" path "/bar"))]
        (is (= 200 (:status resp))
            (str "Did not get 200 for method: "
                 method
                 ", path: "
                 path))))))

(deftest initialize-master!-test
  (let [confdir     (-> resources-dir
                        (fs/copy-dir (ks/temp-dir))
                        (str "/conf"))
        settings    (-> confdir
                        (ca-test-utils/master-settings "master")
                        (assoc :dns-alt-names "onefish,twofish"))
        ca-settings (ca-test-utils/ca-settings (str confdir "/ssl/ca"))]

    (initialize-ssl! settings "master" ca-settings 512)

    (testing "Generated SSL file"
      (doseq [file (vals (ca/settings->ssldir-paths settings))]
        (testing file
          (is (fs/exists? file)))))

    (testing "hostcert"
      (let [hostcert (-> settings :hostcert ca-utils/pem->cert)]
        (is (ca-utils/certificate? hostcert))
        (ca-test-utils/assert-subject hostcert "CN=master")
        (ca-test-utils/assert-issuer hostcert "CN=Puppet CA: localhost")

        (testing "has alt names extension"
          (let [dns-alt-names (ca-utils/get-subject-dns-alt-names hostcert)]
            (is (= #{"master" "onefish" "twofish"} (set dns-alt-names))
                "The Subject Alternative Names extension should contain the
                 master's actual hostname and the hostnames in $dns-alt-names")))

        (testing "is also saved in the CA's $signeddir"
          (let [signedpath (ca/path-to-cert (:signeddir ca-settings) "master")]
            (is (fs/exists? signedpath))
            (is (= hostcert (ca-utils/pem->cert signedpath)))))))

    (testing "localcacert"
      (let [cacert (-> settings :localcacert ca-utils/pem->cert)]
        (is (ca-utils/certificate? cacert))
        (ca-test-utils/assert-subject cacert "CN=Puppet CA: localhost")
        (ca-test-utils/assert-issuer cacert "CN=Puppet CA: localhost")))

    (testing "hostprivkey"
      (let [key (-> settings :hostprivkey ca-utils/pem->private-key)]
        (is (ca-utils/private-key? key))
        (is (= 512 (ca-utils/keylength key)))))

    (testing "hostpubkey"
      (let [key (-> settings :hostpubkey ca-utils/pem->public-key)]
        (is (ca-utils/public-key? key))
        (is (= 512 (ca-utils/keylength key)))))

    (testing "Does not replace files if they all exist"
      (let [files (-> (ca/settings->ssldir-paths settings)
                      (dissoc :certdir :requestdir)
                      (vals))]
        (doseq [f files] (spit f "testable string"))
        (initialize-ssl! settings "master" ca-settings 512)
        (doseq [f files] (is (= "testable string" (slurp f))
                             "File was replaced"))))))
