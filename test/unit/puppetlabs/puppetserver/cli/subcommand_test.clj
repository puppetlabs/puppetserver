(ns puppetlabs.puppetserver.cli.subcommand-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetserver.cli.subcommand :refer :all]))

(deftest environment-test
  (let [fake-config {:jruby-puppet {:gem-home "/fake/path"}}
        fake-env {"PATH" "/bin:/usr/bin",
                  "FOO_DEBUG" "1",
                  "GEM_PATH" "/foo/gem_path:/bar/gem_path",
                  "RUBYLIB" "/foo/ruby/lib",
                  "RUBYOPT" "--foo",
                  "RUBY_OPTS" "--baz"}
        subject (environment fake-config fake-env)]
    (testing "The environment intended for subcommands"
      (is (not (empty? (subject "PATH")))
          "has a non-empty PATH originating from the initial environment")
      (is (= "/bin:/usr/bin" (subject "PATH"))
          "does not modify the PATH environment variable")
      (is (= "1" (subject "FOO_DEBUG"))
          "preserves arbitrary environment vars for the end user")
      (is (= "true" (subject "JARS_NO_REQUIRE"))
          "(SERVER-29) sets JARS_NO_REQUIRE=true")
      (is (= "/fake/path" (subject "GEM_HOME"))
          "has GEM_HOME set to /fake/path from the provided config")
      (is (not (contains? subject "GEM_PATH")) "clears GEM_PATH")
      (is (not (contains? subject "RUBYOPT")) "clears RUBYOPT")
      (is (not (contains? subject "RUBY_OPTS")) "clears RUBY_OPTS")
      (is (not (contains? subject "RUBYLIB")) "clears RUBYLIB"))))
