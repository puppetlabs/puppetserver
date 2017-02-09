(ns puppetlabs.services.jruby.service-macros-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby-puppet]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]))

(deftest simple-with-lock-test
  (testing "using with-lock doesn't throw any errors"
    (let [config (jruby-testutils/jruby-puppet-tk-config
                  (jruby-testutils/jruby-puppet-config))]
      (tk-testutils/with-app-with-config
       app
       (jruby-testutils/jruby-service-and-dependencies-with-mocking config)
       config
       (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
         (jruby-puppet/with-lock
          jruby-service :with-lock-test
          ; Just make sure the macro doesn't fail
          (is (true? true)))
         (is (true? true)))))))
