(ns puppetlabs.services.jruby.jruby-puppet-core-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [schema.test :as schema-test]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils])
  (:import (java.io ByteArrayOutputStream PrintStream ByteArrayInputStream)))

(use-fixtures :once schema-test/validate-schemas)

(def min-config
  {:product
   {:name "puppetserver", :update-server-url "http://localhost:11111"},
   :jruby-puppet
   {:gem-home "./target/jruby-gem-home",
    :ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib" "./ruby/hiera/lib"]}})

(defmacro capture-out
  "capture System.out and return it as the value of :out in the return map.
  The return value of body is available as :return in the return map.

  This macro is intended to be used for JRuby interop.  Please see with-out-str
  for an idiomatic clojure equivalent.

  This macro is not thread safe."
  [& body]
  `(let [return-map# (atom {})
         system-output# (System/out)
         captured-output# (new ByteArrayOutputStream)
         capturing-print-stream# (new PrintStream captured-output#)]
     (try
       (System/setOut capturing-print-stream#)
       (swap! return-map# assoc :return (do ~@body))
       (finally
         (.flush capturing-print-stream#)
         (swap! return-map# assoc :out (.toString captured-output#))
         (System/setOut system-output#)))
     @return-map#))

(deftest default-num-cpus-test
  (testing "1 jruby instance for a 1 or 2-core box"
    (is (= 1 (jruby-core/default-pool-size 1)))
    (is (= 1 (jruby-core/default-pool-size 2))))
  (testing "2 jruby instances for a 3-core box"
    (is (= 2 (jruby-core/default-pool-size 3))))
  (testing "3 jruby instances for a 4-core box"
    (is (= 3 (jruby-core/default-pool-size 4))))
  (testing "4 jruby instances for anything above 5 cores"
    (is (= 4 (jruby-core/default-pool-size 5)))
    (is (= 4 (jruby-core/default-pool-size 8)))
    (is (= 4 (jruby-core/default-pool-size 16)))
    (is (= 4 (jruby-core/default-pool-size 32)))
    (is (= 4 (jruby-core/default-pool-size 64)))))


(deftest add-facter-to-classpath-test
  (letfn [(class-loader-files [] (map #(.getFile %)
                                   (.getURLs
                                     (ClassLoader/getSystemClassLoader))))
          (create-temp-facter-jar [] (-> (ks/temp-dir)
                                       (fs/file jruby-puppet-core/facter-jar)
                                       (fs/touch)
                                       (ks/absolute-path)))
          (temp-dir-as-string [] (-> (ks/temp-dir) (ks/absolute-path)))
          (fs-parent-as-string [path] (-> path (fs/parent) (ks/absolute-path)))
          (jar-in-class-loader-file-list? [jar]
            (some #(= jar %) (class-loader-files)))]
    (testing "facter jar loaded from first position"
      (let [temp-jar (create-temp-facter-jar)]
        (jruby-puppet-core/add-facter-jar-to-system-classloader [(fs-parent-as-string temp-jar)])
        (is (true? (jar-in-class-loader-file-list? temp-jar)))))
    (testing "facter jar loaded from last position"
      (let [temp-jar (create-temp-facter-jar)]
        (jruby-puppet-core/add-facter-jar-to-system-classloader [(temp-dir-as-string)
                                                          (fs-parent-as-string temp-jar)])
        (is (true? (jar-in-class-loader-file-list? temp-jar)))))
    (testing "only first jar loaded when two present"
      (let [first-jar (create-temp-facter-jar)
            last-jar (create-temp-facter-jar)]
        (jruby-puppet-core/add-facter-jar-to-system-classloader [(fs-parent-as-string first-jar)
                                                          (temp-dir-as-string)
                                                          (fs-parent-as-string last-jar)])
        (is (true? (jar-in-class-loader-file-list? first-jar))
          "first jar in the list was unexpectedly not found")
        (is (nil? (jar-in-class-loader-file-list? last-jar))
          "last jar in the list was unexpectedly not found")))
    (testing "class loader files unchanged when no jar found"
      (let [class-loader-files-before-load (class-loader-files)
            _ (jruby-puppet-core/add-facter-jar-to-system-classloader [(temp-dir-as-string)
                                                                (temp-dir-as-string)])
            class-loader-files-after-load (class-loader-files)]
        (is (= class-loader-files-before-load class-loader-files-after-load))))))
