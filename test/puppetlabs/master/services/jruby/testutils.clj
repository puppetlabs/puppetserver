(ns puppetlabs.master.services.jruby.testutils
  (:require [puppetlabs.master.services.jruby.jruby-puppet-core :as jruby-core])
  (:import (com.puppetlabs.master JRubyPuppet JRubyPuppetResponse)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def prod-pool-descriptor {:environment :production})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JRubyPuppet Test util functions

(defn jruby-puppet-config
  "Create a JRubyPuppet pool config which includes the provided list of pool
  descriptions."
  [pools-list]
  {:load-path       ["./ruby/puppet/lib" "./ruby/facter/lib"]
   :master-conf-dir "./test-resources/config/master/conf"
   :jruby-pools     pools-list})

(def default-config-no-size
  (jruby-puppet-config [{:environment "production"}]))

(defn jruby-puppet-config-with-prod-env
  "Create some settings used for creating a JRubyPuppet pool via
  `create-jruby-pool`."
  ([] (jruby-puppet-config-with-prod-env 1))
  ([size]
   (jruby-puppet-config [{:environment "production"
                          :size        size}])))

(defn jruby-puppet-config-with-prod-test-env
  "Create a settings structure which contains a `production` environment of a
  given size as well as an environment named `test` of a given size."
  [prod-size test-size]
  (jruby-puppet-config [{:environment "production"
                         :size prod-size}
                        {:environment "test"
                         :size test-size}]))

(defn create-jruby-instance
  ([]
   (create-jruby-instance (jruby-puppet-config-with-prod-env 1)))
  ([config]
   (jruby-core/create-jruby-instance config)))

(defn create-mock-jruby-instance
  "Creates a mock implementation of the JRubyPuppet interface."
  [_]
  (reify JRubyPuppet
    (handleRequest [this request]
      (JRubyPuppetResponse. 0 nil nil nil))
    (getSetting [this setting]
      (Object.))))

(defn mock-jruby-fixture
  "Test fixture which changes the behavior of the JRubyPool to create
  mock JRubyPuppet instances."
  [f]
  (with-redefs
    [jruby-core/create-jruby-instance create-mock-jruby-instance]
    (f)))
