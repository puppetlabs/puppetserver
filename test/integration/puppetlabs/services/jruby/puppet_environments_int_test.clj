(ns puppetlabs.services.jruby.puppet-environments-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.puppetserver.testutils :as testutils :refer
             [ssl-request-options catalog-request-options get-catalog]]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.kitchensink.core :as ks]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/puppet_environments_int_test")

(use-fixtures :once
              (testutils/with-puppet-conf
                (fs/file test-resources-dir "puppet.conf")))

(def num-jrubies 2)

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(defn get-catalog-and-borrow-jruby
  "Gets a catalog, and then borrows a JRuby instance from a pool to ensure that
  a subsequent catalog request will be directed to a different JRuby.  Returns a
  map containing both the catalog and the JRuby instance.  This function
  relies on the fact that we are using a LIFO algorithm for allocating JRuby
  instances to handle requests."
  [borrow-jruby-fn]
  (let [catalog (get-catalog)
        jruby   (borrow-jruby-fn)]
    {:catalog catalog
     :jruby   jruby}))

(defn get-catalog-and-return-jruby
  "Given a map containing a catalog and a JRuby instance, return the JRuby
  instance to the pool and return the catalog."
  [return-jruby-fn m]
  (return-jruby-fn (:jruby m))
  (:catalog m))

(defn get-catalog-from-each-jruby
  "Iterates through all of the JRuby instances and gets a catalog from each of
  them.  Returns the sequence of catalogs."
  [borrow-jruby-fn return-jruby-fn]
  ;; iterate over all of the jrubies and call get-catalog-and-borrow-jruby.  It's
  ;; important that this is not done lazily, otherwise the jrubies could be returned
  ;; to the pool before the next borrow occurs.
  (let [jrubies-and-catalogs (doall
                               (repeatedly
                                 num-jrubies
                                (partial get-catalog-and-borrow-jruby borrow-jruby-fn)))]
    ;; now we can return the jrubies to the pool, and return the seq of catalogs
    ;; to our caller
    (map (partial get-catalog-and-return-jruby return-jruby-fn)
         jrubies-and-catalogs)))

;; This test is written in a way that relies on knowledge about
;; the underlying implementation of the JRuby pool. That is admittedly
;; not ideal, but we discussed it at length and agreed that the test has
;; value in terms of simulating an end user's experience, which could not
;; be achieved w/o some such assumptions, and thus is worth keeping.
(deftest ^:integration environment-flush-integration-test
  (testing "environments are flushed after marking expired"
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
    (testutils/write-site-pp-file "include foo")
    ;; Now we define the class; just a notify with 'hello1'.
    (testutils/write-foo-pp-file "class foo { notify {'hello1': } }")
    ;; Now we're going to start up puppetserver with 2 jrubies.  We need
    ;; two of them so that we can illustrate that the cache can
    ;; be out of sync between the two of them.
    (bootstrap/with-puppetserver-running app {:jruby-puppet
                                              {:gem-path gem-path
                                               :max-active-instances num-jrubies}}
      (let [jruby-service   (tk-app/get-service app :JRubyPuppetService)
            borrow-jruby-fn (partial jruby-testutils/borrow-instance jruby-service
                              :environment-flush-integration-test)
            return-jruby-fn (fn [instance] (jruby-testutils/return-instance
                                             jruby-service
                                             instance
                                             :environment-flush-integration-test))]
        ;; wait for all of the jrubies to be ready so that we can
        ;; validate cache state differences between them.
        (jruby-testutils/wait-for-jrubies app)

        (testing "flush called when no jrubies are borrowed"
          ;;; Now we grab a catalog from the first jruby instance.  This
          ;;; catalog should contain the 'hello1' notify, and will cause
          ;;; the first jruby instance to cache the manifests.
          (let [catalog1 (get-catalog)]
            (is (testutils/catalog-contains? catalog1 "Notify" "hello1"))
            (is (not (testutils/catalog-contains? catalog1 "Notify" "hello2"))))

          ;; Now we modify the class definition to have a 'hello2' notify,
          ;; instead of 'hello1'.
          (testutils/write-foo-pp-file "class foo { notify {'hello2': } }")

          ;; Now we grab a catalog from both of the jrubies.  One should have the
          ;; old, cached state, and one should have the new state.
          (let [catalogs (get-catalog-from-each-jruby borrow-jruby-fn return-jruby-fn)]
            (is (= 1 (testutils/num-catalogs-containing catalogs "Notify" "hello1")))
            (is (= 1 (testutils/num-catalogs-containing catalogs "Notify" "hello2"))))

          ;; Now, make a DELETE request to the /environment-cache endpoint.
          ;; This flushes Puppet's cache for all environments.
          (let [response (http-client/delete
                           "https://localhost:8140/puppet-admin-api/v1/environment-cache"
                           ssl-request-options)]
            (testing "A successful DELETE request to /environment-cache returns an HTTP 204"
              (is (= 204 (:status response))
                (ks/pprint-to-string response))))

          ;; Now if we get catalogs from both of the JRubies again, we should get
          ;; the 'hello2' catalog from both, since the cache should have been
          ;; cleared.
          (let [catalogs (get-catalog-from-each-jruby borrow-jruby-fn return-jruby-fn)]
            (is (= 0 (testutils/num-catalogs-containing catalogs "Notify" "hello1")))
            (is (= 2 (testutils/num-catalogs-containing catalogs "Notify" "hello2")))))

        (testing "flush called when a jruby is borrowed"
          ;; change the resource again
          (testutils/write-foo-pp-file "class foo { notify {'hello3': } }")
          ;; borrow an instance
          (let [instance (borrow-jruby-fn)]
            ;; flush the cache
            (try
              (let [response (http-client/delete
                               "https://localhost:8140/puppet-admin-api/v1/environment-cache"
                               ssl-request-options)]
                (is (= 204 (:status response)) (ks/pprint-to-string response)))
              (finally
                ;; return the instance after the cache flush
                (return-jruby-fn instance))))

          (let [catalogs (get-catalog-from-each-jruby borrow-jruby-fn return-jruby-fn)]
            (is (= 0 (testutils/num-catalogs-containing catalogs "Notify" "hello1")))
            (is (= 0 (testutils/num-catalogs-containing catalogs "Notify" "hello2")))
            (is (= 2 (testutils/num-catalogs-containing catalogs "Notify" "hello3")))))))))

(deftest ^:integration single-environment-flush-integration-test
  (testing "a single environment is flushed after marking expired"
    (testutils/write-site-pp-file "include foo")
    (testutils/write-foo-pp-file "class foo { notify {'hello1': } }")
    (bootstrap/with-puppetserver-running app {:jruby-puppet
                                              {:gem-path gem-path
                                               :max-active-instances 1}}
      ;;; Validate that the catalog has `hello1`
      (let [catalog1 (get-catalog)]
        (is (testutils/catalog-contains? catalog1 "Notify" "hello1"))
        (is (not (testutils/catalog-contains? catalog1 "Notify" "hello2"))))

      ;; Now we modify the class definition to have a 'hello2' notify,
      ;; instead of 'hello1'.
      (testutils/write-foo-pp-file "class foo { notify {'hello2': } }")

      ;; Now, make a DELETE request to the /environment-cache endpoint, specifying
      ;; an environment OTHER THAN the production environment.  This should not
      ;; cause the cache to be flushed for the production environment.
      (let [response (http-client/delete
                       "https://localhost:8140/puppet-admin-api/v1/environment-cache?environment=foo"
                       ssl-request-options)]
        (is (= 204 (:status response)) (ks/pprint-to-string response)))

      ;;; Validate that the catalog still has `hello1`
      (let [catalog1 (get-catalog)]
        (is (testutils/catalog-contains? catalog1 "Notify" "hello1"))
        (is (not (testutils/catalog-contains? catalog1 "Notify" "hello2"))))

      (let [response (http-client/delete
                       "https://localhost:8140/puppet-admin-api/v1/environment-cache?environment=production"
                       ssl-request-options)]
        (is (= 204 (:status response)) (ks/pprint-to-string response)))

      ;;; Validate that the catalog now has `hello2`
      (let [catalog1 (get-catalog)]
        (is (not (testutils/catalog-contains? catalog1 "Notify" "hello1")))
        (is (testutils/catalog-contains? catalog1 "Notify" "hello2"))))))
