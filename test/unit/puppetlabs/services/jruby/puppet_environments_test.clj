(ns puppetlabs.services.jruby.puppet-environments-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]))

(defn contains-environment?
  [reg env-name]
  (contains? (-> reg puppet-env/environment-state deref) env-name))

(defn expired?
  [reg env-name]
  (-> reg puppet-env/environment-state deref env-name :expired))

(deftest environment-registry-test
  (testing "environments are not expired by default"
    (let [reg (puppet-env/environment-registry)]
      (.registerEnvironment reg "foo")
      (is (false? (.isExpired reg "foo")))
      (is (false? (expired? reg :foo)))
      (.registerEnvironment reg "bar")
      (is (false? (.isExpired reg "foo")))
      (is (false? (expired? reg :foo)))
      (is (false? (.isExpired reg "bar")))
      (is (false? (expired? reg :bar)))))
  (testing "mark-all-environments-expired"
    (let [reg (puppet-env/environment-registry)]
      (.registerEnvironment reg "foo")
      (.registerEnvironment reg "bar")
      (is (false? (expired? reg :foo)))
      (is (false? (expired? reg :bar)))
      (puppet-env/mark-all-environments-expired! reg)
      (is (true? (.isExpired reg "foo")))
      (is (true? (expired? reg :foo)))
      (is (true? (.isExpired reg "bar")))
      (is (true? (expired? reg :bar)))))
  (testing "mark-environment-expired"
    (let [reg (puppet-env/environment-registry)]
      (.registerEnvironment reg "foo")
      (.registerEnvironment reg "bar")
      (is (false? (expired? reg :foo)))
      (is (false? (expired? reg :bar)))
      (puppet-env/mark-environment-expired! reg "foo")
      (is (true? (.isExpired reg "foo")))
      (is (true? (expired? reg :foo)))
      (is (false? (.isExpired reg "bar")))
      (is (false? (expired? reg :bar)))
      (puppet-env/mark-environment-expired! reg "bar")
      (is (true? (.isExpired reg "foo")))
      (is (true? (expired? reg :foo)))
      (is (true? (.isExpired reg "bar")))
      (is (true? (expired? reg :bar)))))
  (testing "removing and re-registering an environment clears staleness"
    (let [reg (puppet-env/environment-registry)]
      (.registerEnvironment reg "foo")
      (is (false? (.isExpired reg "foo")))
      (is (false? (expired? reg :foo)))
      (puppet-env/mark-all-environments-expired! reg)
      (is (true? (.isExpired reg "foo")))
      (is (true? (expired? reg :foo)))
      (is (true? (contains-environment? reg :foo)))
      (.removeEnvironment reg "foo")
      (is (false? (contains-environment? reg :foo)))
      (.registerEnvironment reg "foo")
      (is (false? (.isExpired reg "foo")))
      (is (false? (expired? reg :foo)))))
  (testing "unregistered environments are expired by default"
    (let [reg (puppet-env/environment-registry)]
      (is (false? (contains-environment? reg :foo)))
      (is (true? (.isExpired reg "foo")))
      ; expired? returns nil because it bypasses the
      ; EnvironmentRegistry.isExpired fn and accesses the underlying data
      ; directly, which of course not contain :foo
      (is (nil? (expired? reg :foo))))))


