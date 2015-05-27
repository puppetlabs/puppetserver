(ns puppetlabs.services.jruby.jruby-puppet-core-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core])
  (:import (java.io ByteArrayOutputStream PrintStream ByteArrayInputStream)))

(use-fixtures :once schema-test/validate-schemas)

(def min-config
  {:product
   {:name "puppet-server", :update-server-url "http://localhost:11111"},
   ; os-settings has merged into jruby-puppet as of puppetserver 2.0
   :os-settings
   {:ruby-load-path ["./ruby/puppet/lib" "./ruby/facter/lib"]},
   :jruby-puppet
   {:gem-home "./target/jruby-gem-home"},
   :certificate-authority {:certificate-status {:client-whitelist []}}})

(defmacro with-stdin-str
  "Evaluates body in a context in which System/in is bound to a fresh
  input stream initialized with the string s.  The return value of evaluating
  body is returned."
  [s & body]
  `(let [system-input# (System/in)
         string-input# (new ByteArrayInputStream (.getBytes ~s))]
     (try
       (System/setIn string-input#)
       ~@body
       (finally (System/setIn system-input#)))))

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

(deftest ^:integration cli-run!-test
  (testing "jruby cli command output"
    (testing "gem env (SERVER-262)"
      (let [m (capture-out (jruby-core/cli-run! min-config "gem" ["env"]))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        ; The choice of SHELL PATH is arbitrary, just need something to scan for
        (is (re-find #"SHELL PATH:" out))))
    (testing "gem list"
      (let [m (capture-out (jruby-core/cli-run! min-config "gem" ["list"]))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        ; The choice of json is arbitrary, just need something to scan for
        (is (re-find #"\bjson\b" out))))
    (testing "irb"
      (let [m (capture-out
                (with-stdin-str "puts %{HELLO}"
                  (jruby-core/cli-run! min-config "irb" ["-f"])))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        (is (re-find #"\nHELLO\n" out)))
      (let [m (capture-out
                (with-stdin-str "Kernel.exit(42)"
                  (jruby-core/cli-run! min-config "irb" ["-f"])))
            {:keys [return _]} m
            exit-code (.getStatus return)]
        (is (= 42 exit-code))))
    (testing "irb with -r puppet"
      (let [m (capture-out
                (with-stdin-str "puts %{VERSION: #{Puppet.version}}"
                  (jruby-core/cli-run! min-config "irb" ["-r" "puppet" "-f"])))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        (is (re-find #"VERSION: \d+\.\d+\.\d+" out))))
    (testing "non existing subcommand returns nil"
      (is (nil? (jruby-core/cli-run! min-config "doesnotexist" []))))))

(deftest ^:integration cli-ruby!-test
  (testing "jruby cli command output"
    (testing "ruby -r puppet"
      (let [m (capture-out
                (with-stdin-str "puts %{VERSION: #{Puppet.version}}"
                  (jruby-core/cli-ruby! min-config ["-r" "puppet"])))
            {:keys [return out]} m
            exit-code (.getStatus return)]
        (is (= 0 exit-code))
        (is (re-find #"VERSION: \d+\.\d+\.\d+" out))))))
