(ns puppetlabs.services.jruby.puppet-environments-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/puppet_environments_int_test")

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
                (fs/file test-resources-dir "puppet.conf")))

(defn pem-file
  [& args]
  (str (apply fs/file bootstrap/master-conf-dir "ssl" args)))

(def ca-cert
  (pem-file "certs" "ca.pem"))

(def localhost-cert
  (pem-file "certs" "localhost.pem"))

(def localhost-key
  (pem-file "private_keys" "localhost.pem"))

(defn write-site-pp-file
  [site-pp-contents]
  (let [site-pp-file (fs/file bootstrap/master-conf-dir "environments" "production" "manifests" "site.pp")]
    (fs/mkdirs (fs/parent site-pp-file))
    (spit site-pp-file site-pp-contents)))

(defn write-foo-pp-file
  [foo-pp-contents]
  (let [foo-pp-file (fs/file bootstrap/master-conf-dir
                                  "environments"
                                  "production"
                                  "modules"
                                  "foo"
                                  "manifests"
                                  "init.pp")]
    (fs/mkdirs (fs/parent foo-pp-file))
    (spit foo-pp-file foo-pp-contents)))

(def request-options
  {:ssl-cert      localhost-cert
   :ssl-key       localhost-key
   :ssl-ca-cert   ca-cert
   :headers       {"Accept" "pson"}
   :as            :text})

(defn wait-for-jrubies
  [app num-jrubies]
  (while (< (count (jruby-testutils/jruby-pool app))
            num-jrubies)
    (Thread/sleep 100)))

(defn get-catalog
  []
  (-> (http-client/get
        "https://localhost:8140/production/catalog/localhost"
        request-options)
      :body
      json/parse-string))

(defn resource-matches?
  [resource-type resource-title resource]
  (and (= resource-type (resource "type"))
       (= resource-title (resource "title"))))

(defn catalog-contains?
  [catalog resource-type resource-title]
  (let [resources (get-in catalog ["data" "resources"])]
    (some (partial resource-matches? resource-type resource-title) resources)))

(deftest ^:integration environment-flush-integration-test
  (testing "environments are flushed after marking stale"
    ;; This test is a bit complicated, so warrants some 'splainin.
    ;;
    ;; Note that we start off with a puppet.conf file from the fixture
    ;; above, which enables directory environments and sets the
    ;; timeout to 'unlimited' so that we know that puppet will
    ;; never expire entries from the environment cache without
    ;; our help.
    ;;
    ;; The first thing we do is write out a site.pp that includes a
    ;; class that we'll create for our environment.
    (write-site-pp-file "include foo")
    ;; Now we define the class; just a notify with 'hello1'.
    (write-foo-pp-file "class foo { notify {'hello1': } }")
    ;; Now we're going to start up puppetserver with 2 jrubies.  We need
    ;; two of them so that we can illustrate that the cache can
    ;; be out of sync between the two of them.
    (bootstrap/with-puppetserver-running app {:jruby-puppet
                                               {:max-active-instances 2}}
      ;; if we start making requests before we know that all of the
      ;; jruby instances are ready, we won't be able to predict which
      ;; instance is handling our request, so we need to wait for them.
      (wait-for-jrubies app 2)
      ;; Now we grab a catalog from the first jruby instance.  This
      ;; catalog should contain the 'hello1' notify, and will cause
      ;; the first jruby instance to cache the manifests.
      (let [catalog1 (get-catalog)]
        (is (catalog-contains? catalog1 "Notify" "hello1"))
        (is (not (catalog-contains? catalog1 "Notify" "hello2"))))
      ;; Now we modify the class definition to have a 'hello2' notify,
      ;; instead of 'hello1'.
      (write-foo-pp-file "class foo { notify {'hello2': } }")
      ;; Retrieving the catalog a second time will route us to the
      ;; second jruby instance, which hasn't cached the manifest yet,
      ;; so we should get 'hello2'.
      (let [catalog2 (get-catalog)]
        (is (not (catalog-contains? catalog2 "Notify" "hello1")))
        (is (catalog-contains? catalog2 "Notify" "hello2")))
      ;; The next catalog request goes back to the first jruby instance,
      ;; which still has 'hello1' cached.
      (let [catalog1 (get-catalog)]
        (is (catalog-contains? catalog1 "Notify" "hello1"))
        (is (not (catalog-contains? catalog1 "Notify" "hello2"))))
      ;; Now we call our code to mark the cache as stale.
      (jruby-testutils/mark-all-environments-stale app)
      ;; Next catalog request goes to the second jruby instance,
      ;; where we should see 'hello2' regardless of caching.
      (let [catalog2 (get-catalog)]
        (is (not (catalog-contains? catalog2 "Notify" "hello1")))
        (is (catalog-contains? catalog2 "Notify" "hello2")))
      ;; And the final catalog request goes back to the first jruby instance,
      ;; where we expect the cache to have been cleared so that we will get 'hello2'.
      (let [catalog1 (get-catalog)]
        (is (not (catalog-contains? catalog1 "Notify" "hello1")))
        (is (catalog-contains? catalog1 "Notify" "hello2"))))))


