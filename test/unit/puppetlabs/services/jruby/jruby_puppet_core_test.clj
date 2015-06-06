(ns puppetlabs.services.jruby.jruby-puppet-core-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [schema.test :as schema-test]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

(use-fixtures :once schema-test/validate-schemas)

(def min-config
  {:product
   {:name "puppet-server", :update-server-url "http://localhost:11111"},
   :jruby-puppet
   {:gem-home "./target/jruby-gem-home",
    :ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"]},
   :certificate-authority {:certificate-status {:client-whitelist []}}})

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

(deftest initialize-config-test
  (let [subject (fn [] (jruby-core/initialize-config min-config))]
    (testing "master-{conf,var}-dir settings are optional"
      (is (= "/etc/puppetlabs/puppet" (:master-conf-dir (subject))))
      (is (= "/opt/puppetlabs/server/data/puppetserver" (:master-var-dir (subject)))))
    (testing "(SERVER-647) master-{code,run,log}-dir settings are optional"
      (is (= "/etc/puppetlabs/code" (:master-code-dir (subject))))
      (is (= "/var/run/puppetlabs/puppetserver" (:master-run-dir (subject))))
      (is (= "/var/log/puppetlabs/puppetserver" (:master-log-dir (subject)))))))

(deftest add-facter-to-classpath-test
  (let [class-loader-files (fn [] (map #(.getFile %)
                                    (.getURLs
                                      (ClassLoader/getSystemClassLoader))))
        create-temp-facter-jar (fn [] (-> (ks/temp-dir)
                                        (fs/file jruby-core/facter-jar)
                                        (fs/touch)
                                        (fs/absolute-path)))
        temp-dir-as-string (fn [] (-> (ks/temp-dir) (fs/absolute-path)))
        fs-parent-as-string (fn [path]
                              (-> path
                                (fs/parent)
                                (fs/absolute-path)))
        jar-in-class-loader-file-list? (fn [jar]
                                         (some #(= jar %)
                                           (class-loader-files)))]
    (testing "facter jar loaded from first position"
      (let [temp-jar (create-temp-facter-jar)]
        (jruby-core/add-facter-jar-to-system-classloader [(fs-parent-as-string temp-jar)])
        (is (true? (jar-in-class-loader-file-list? temp-jar)))))
    (testing "facter jar loaded from last position"
      (let [temp-jar (create-temp-facter-jar)]
        (jruby-core/add-facter-jar-to-system-classloader [(temp-dir-as-string)
                                             (fs-parent-as-string temp-jar)])
        (is (true? (jar-in-class-loader-file-list? temp-jar)))))
    (testing "only first jar loaded when two present"
      (let [first-jar (create-temp-facter-jar)
            last-jar (create-temp-facter-jar)]
        (jruby-core/add-facter-jar-to-system-classloader [(fs-parent-as-string first-jar)
                                             (temp-dir-as-string)
                                             (fs-parent-as-string last-jar)])
        (is (true? (jar-in-class-loader-file-list? first-jar))
          "first jar in the list was unexpectedly not found")
        (is (nil? (jar-in-class-loader-file-list? last-jar))
          "last jar in the list was unexpectedly not found")))
    (testing "class loader files unchanged when no jar found"
      (let [class-loader-files-before-load (class-loader-files)
            _ (jruby-core/add-facter-jar-to-system-classloader [(temp-dir-as-string)
                                                   (temp-dir-as-string)])
            class-loader-files-after-load (class-loader-files)]
        (is (= class-loader-files-before-load class-loader-files-after-load))))))
