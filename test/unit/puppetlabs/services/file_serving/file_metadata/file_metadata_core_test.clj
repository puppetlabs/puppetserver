(ns puppetlabs.services.file-serving.file-metadata.file-metadata-core-test
  (:import [java.nio.file Files]
           [java.nio.file Paths]
           [java.nio.file LinkOption])
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [clojure.string :as string]
            [cheshire.core :as json]
            [puppetlabs.services.file-serving.config.puppet-fileserver-config-core :as config]
            [puppetlabs.services.file-serving.file-metadata.file-metadata-core :refer :all]
            [puppetlabs.services.protocols.puppet-fileserver-config :refer :all]))

(def test-dir "./dev-resources/puppetlabs/services/file_serving/file_metadata/")
(def fileserver-conf (str test-dir "fileserver.conf"))

(defn- test-attributes
  [file]
  (let [path (Paths/get test-dir (into-array String ["test" file]))
                           attrs "unix:uid,gid,permissions,creationTime"]
                       (into {} (Files/readAttributes path attrs (into-array LinkOption [])))))

(def test-file-attributes (test-attributes "file.txt"))
(def test-link-attributes (test-attributes "link.txt"))
(def test-directory-attributes (test-attributes "directory"))

(deftest test-file-metadata
  (let [mount-path (config/find-mount (config/fileserver-parse fileserver-conf) "test/file.txt")
        metadata (get-metadata mount-path "manage" "use")]

    (testing "has a document_type"
      (is (= (metadata :document_type)
             "FileMetadata")))

    (testing "has a metadata version"
      (is (= (get-in metadata [:metadata :api_version])
             1)))

    (testing "has a data type"
      (is (= (get-in metadata [:data :type])
             "file")))

    (testing "has data checksum"
      (is (= (get-in metadata [:data :checksum :type])
             "md5"))
      (is (= (get-in metadata [:data :checksum :value])
             "{md5}e807f1fcf82d132f9bb018ca6738a19f")))

    (testing "has correct unix attributes"
      (is (= (get-in metadata [:data :owner])
             (test-file-attributes "uid")))
      (is (= (get-in metadata [:data :group])
             (test-file-attributes "gid")))
      (is (= (get-in metadata [:data :mode])
             0644)))))

(deftest test-directory-metadata
  (let [mount-path (config/find-mount (config/fileserver-parse fileserver-conf) "test/directory")
        metadata (get-metadata mount-path "manage" "use")]

    (testing "has a document_type"
      (is (= (metadata :document_type)
             "FileMetadata")))

    (testing "has a metadata version"
      (is (= (get-in metadata [:metadata :api_version])
             1)))

    (testing "has a data type"
      (is (= (get-in metadata [:data :type])
             "directory")))

    (testing "has data checksum"
      (is (= (get-in metadata [:data :checksum :type])
             "ctime"))
      (is (= (.getTime (ruby->date (string/replace-first (get-in metadata [:data :checksum :value]) "{ctime}" "")))
             (.toMillis (test-directory-attributes "creationTime")))))

    (testing "has correct unix attributes"
      (is (= (get-in metadata [:data :owner])
             (test-directory-attributes "uid")))
      (is (= (get-in metadata [:data :group])
             (test-directory-attributes "gid")))
      (is (= (get-in metadata [:data :mode])
             0755)))))


(deftest test-link-metadata
  (let [mount-path (config/find-mount (config/fileserver-parse fileserver-conf) "test/link.txt")
        metadata (get-metadata mount-path "manage" "use")]

    (testing "has a document_type"
      (is (= (metadata :document_type)
             "FileMetadata")))

    (testing "has a metadata version"
      (is (= (get-in metadata [:metadata :api_version])
             1)))

    (testing "has a data type"
      (is (= (get-in metadata [:data :type])
             "link")))

    (testing "has a data destination"
      (is (Files/isSameFile (Paths/get (get-in metadata [:data :destination]) (into-array String []))
             (Paths/get test-dir (into-array ["test/file.txt"])))))

    (testing "has data checksum"
      (is (= (get-in metadata [:data :checksum :type])
             "md5"))
      (is (= (get-in metadata [:data :checksum :value])
             "{md5}e807f1fcf82d132f9bb018ca6738a19f")))

    (testing "has correct unix attributes"
      (is (= (get-in metadata [:data :owner])
             (test-link-attributes "uid")))
      (is (= (get-in metadata [:data :group])
             (test-link-attributes "gid")))
      (is (= (get-in metadata [:data :mode])
             0755)))))

;Mock implementation of the PuppetFileserverConfigService protocol so that
;we don't have to require the puppet-fileserver-config-service tk service.
;It simply returns a made of find-mount return
(defrecord MockedFileserver
  [path]
  PuppetFileserverConfigService
  (find-mount [_ _]
    [{ :path (str test-dir "test") :acl [:allow "*"] } path])
  (allowed? [_ _ _] true))

(defn- mocked-file-mount [path] (MockedFileserver. path))

(defn- body
  [response]
  "Extracts and parse a response body as a clojure object for better introspection."
  (-> (:body response)
      (json/parse-string true)))

(defn- checksum
  [response]
  "Returns the checksum in a ring response"
  (get-in (body response) [:data :checksum :value]))

(deftest test-file-metadata-handler
  (let [app (fn [path] (build-ring-handler (mocked-file-mount path)))
        request (fn r ([path-info path] (r :get path-info path))
                  ([method path-info path] ((app path) (mock/request method path-info))))]
    (testing "the file-metadata routing and endpoint"
      (is (= (checksum (request "/production/file_metadata/test/file.txt" "file.txt"))
             "{md5}e807f1fcf82d132f9bb018ca6738a19f")))

    (testing "for file that doesn't exist"
      (is (= (:status (request "/production/file_metadata/test/inexistant.txt" "inexistant.txt"))
             404)))))

