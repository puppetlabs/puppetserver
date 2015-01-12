(ns puppetlabs.puppetserver.cli.gem-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.cli.gem :refer :all]))

(deftest cli-gem-environment-test
  (let [fake-config {:jruby-puppet {:gem-home "/fake/path"}}
        fake-env {"PATH" "/bin:/usr/bin", "FOO_DEBUG" "1"}]
    (testing "The environment intended for the gem subcommand"
      (is (not (empty? ((cli-gem-environment fake-config fake-env) "PATH")))
          "has a non-empty PATH originating from the System")
      (is (= "/bin:/usr/bin" ((cli-gem-environment fake-config fake-env)
                               "PATH"))
          "does not modify the PATH environment variable")
      (is (= "/fake/path" ((cli-gem-environment fake-config fake-env)
                            "GEM_HOME"))
          "has GEM_HOME set to /fake/path from the provided config")
      (is (= "1" ((cli-gem-environment fake-config fake-env)
                      "FOO_DEBUG"))
          "preserves arbitrary environment vars for the end user"))))
