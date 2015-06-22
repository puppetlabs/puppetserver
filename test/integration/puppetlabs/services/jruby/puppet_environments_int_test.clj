(ns puppetlabs.services.jruby.puppet-environments-int-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/jruby/puppet_environments_int_test")

(use-fixtures :once
              (jruby-testutils/with-puppet-conf
                (fs/file test-resources-dir "puppet.conf")))

(def ca-cert
  (bootstrap/pem-file "certs" "ca.pem"))

(def localhost-cert
  (bootstrap/pem-file "certs" "localhost.pem"))

(def localhost-key
  (bootstrap/pem-file "private_keys" "localhost.pem"))

(def num-jrubies 2)

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

(def ssl-request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert})

(def catalog-request-options
  (merge
    ssl-request-options
    {:headers     {"Accept" "pson"}
     :as          :text}))

(defn service-context
  [app service-id]
  (-> (tk-app/app-context app)
      deref
      service-id))

(defn wait-for-jrubies
  [app]
  (let [pool-context (-> (service-context app :JRubyPuppetService)
                         :pool-context)]
    (while (< (count (jruby-core/pool->vec pool-context))
              num-jrubies)
      (Thread/sleep 100))))

(defn get-catalog
  "Make an HTTP request to get a catalog."
  []
  (-> (http-client/get
        "https://localhost:8140/puppet/v3/catalog/localhost?environment=production"
        catalog-request-options)
      :body
      json/parse-string))

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

(defn resource-matches?
  [resource-type resource-title resource]
  (and (= resource-type (resource "type"))
       (= resource-title (resource "title"))))

(defn catalog-contains?
  [catalog resource-type resource-title]
  (let [resources (get catalog "resources")]
    (some (partial resource-matches? resource-type resource-title) resources)))

(defn num-catalogs-containing
  [catalogs resource-type resource-title]
  (count (filter #(catalog-contains? % resource-type resource-title) catalogs)))

;; This test is written in a way that relies on knowledge about
;; the underlying implementation of the JRuby pool. That is admittedly
;; not ideal, but we discussed it at length and agreed that the test has
;; value in terms of simulating an end user's experience, which could not
;; be achieved w/o some such assumptions, and thus is worth keeping.
