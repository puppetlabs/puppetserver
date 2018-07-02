(ns puppetlabs.services.jruby.jruby-puppet-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [me.raynes.fs :as fs]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(defn jruby-service-test-config
  [pool-size]
  (jruby-testutils/jruby-puppet-tk-config
    (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})))

(deftest environment-class-info-tags
  (testing "environment-class-info-tags cache has proper data"
    ;; This test uses a mock JRubyPoolManagerService.  Where these tests are
    ;; largely about validating the content of the environment cache, whose
    ;; implementation lives in Clojure, having "real" JRuby instances running
    ;; in the application stack does not seem essential for these tests.
    ;;
    ;; There are related tests in other namespaces which do use real JRubyPuppet
    ;; instances:
    ;;
    ;; * puppetlabs.services.jruby.class-info-test - Makes direct calls on a
    ;;   "real" JRubyPuppet to validate that the environment class content
    ;;   matches the manifest content on disk.
    ;;
    ;; * puppetlabs.services.master.environment-classes-int-test - Stands up
    ;;   a full application stack and makes calls to the "environment_classes"
    ;;   HTTP endpoint which exercise both the Clojure-level environment class
    ;;   cache and "real" JRubyPuppet instances for doing class info parsing.
    (let [config (jruby-service-test-config 1)]
      (bootstrap/with-app-with-config
       app
       (jruby-testutils/jruby-service-and-dependencies-with-mocking config)
       config
       (let [service (app/get-service app :JRubyPuppetService)
             production-cache-id-before-first-update
             (jruby-protocol/get-environment-class-info-cache-generation-id!
              service
              "production")]
         (testing "when environment not previously loaded to cache"
           (is (nil? (jruby-protocol/get-environment-class-info-tag
                      service
                      "production")))
           (is (= 1 production-cache-id-before-first-update)))
         (testing "when environment info first set to cache"
           (jruby-protocol/set-environment-class-info-tag!
            service
            "production"
            "1234prod"
            production-cache-id-before-first-update)
           (is (= "1234prod" (jruby-protocol/get-environment-class-info-tag
                              service
                              "production")))
           (is (nil? (jruby-protocol/get-environment-class-info-tag
                      service
                      "test")))
           (jruby-protocol/set-environment-class-info-tag!
            service
            "test"
            "1234test"
            (jruby-protocol/get-environment-class-info-cache-generation-id!
             service
             "test"))
           (is (= "1234prod" (jruby-protocol/get-environment-class-info-tag
                              service
                              "production")))
           (is (= "1234test" (jruby-protocol/get-environment-class-info-tag
                              service
                              "test"))))
         (let [production-cache-id-after-first-update
               (jruby-protocol/get-environment-class-info-cache-generation-id!
                service
                "production")
               test-cache-id-after-first-update
               (jruby-protocol/get-environment-class-info-cache-generation-id!
                service
                "test")]
           (testing "when environment info reset in the cache"
             (is (= 2 production-cache-id-after-first-update))
             (is (= 2 test-cache-id-after-first-update))
             (jruby-protocol/set-environment-class-info-tag!
              service
              "production"
              "5678prod"
              production-cache-id-after-first-update)
             (is (= "5678prod" (jruby-protocol/get-environment-class-info-tag
                                service
                                "production")))
             (is (= "1234test" (jruby-protocol/get-environment-class-info-tag
                                service
                                "test")))
             (is (= test-cache-id-after-first-update
                    (jruby-protocol/get-environment-class-info-cache-generation-id!
                     service
                     "test")))
             (testing "and environment expired between get and corresponding set"
               (let [production-cache-id-before-marked-expired
                     (jruby-protocol/get-environment-class-info-cache-generation-id!
                      service
                      "production")
                     _ (jruby-protocol/mark-environment-expired! service
                                                                 "production")
                     production-cache-id-after-marked-expired
                     (jruby-protocol/get-environment-class-info-cache-generation-id!
                      service
                      "production")]
                 (is (not= production-cache-id-after-first-update
                           production-cache-id-before-marked-expired))
                 (is (not= production-cache-id-before-marked-expired
                           production-cache-id-after-marked-expired))
                 (is (= nil (jruby-protocol/get-environment-class-info-tag
                             service
                             "production"))
                     (str "Tag was unexpectedly non-nil, however, it should have "
                          "been because of the prior call to "
                          "`mark-environment-expired`"))
                 (jruby-protocol/set-environment-class-info-tag!
                  service
                  "production"
                  "89abprod"
                  production-cache-id-before-marked-expired)
                 (is (= nil (jruby-protocol/get-environment-class-info-tag
                             service
                             "production"))
                     (str "Tag should not have been changed by the prior set "
                          "since the environment was marked expired after the "
                          "cache was read for "
                          "`production-cache-id-before-marked-expired`"))
                 (is (= production-cache-id-after-marked-expired
                        (jruby-protocol/get-environment-class-info-cache-generation-id!
                         service
                         "production"))
                     (str "Cache id should not have been changed by the prior "
                          "set since the environment was marked expired after "
                          "cache was read for "
                          "`production-cache-id-before-marked-expired`")))))
           (testing "when an individual environment is marked expired"
             (let [production-cache-id-before-marked-expired
                   (jruby-protocol/get-environment-class-info-cache-generation-id!
                    service
                    "production")]
               (jruby-protocol/mark-environment-expired! service "production")
               (is (nil? (jruby-protocol/get-environment-class-info-tag
                          service
                          "production")))
               (is (not= production-cache-id-before-marked-expired
                         (jruby-protocol/get-environment-class-info-cache-generation-id!
                          service
                          "production"))))
             (is (= "1234test" (jruby-protocol/get-environment-class-info-tag
                                service
                                "test")))
             (is (= test-cache-id-after-first-update
                    (jruby-protocol/get-environment-class-info-cache-generation-id!
                     service
                     "test"))))
           (testing "when all environments are marked expired"
             (let [production-cache-id-before-set-tag
                   (jruby-protocol/get-environment-class-info-cache-generation-id!
                    service
                    "production")]
               (jruby-protocol/set-environment-class-info-tag!
                service
                "production"
                "8910prod"
                production-cache-id-before-set-tag)
               (is (= "8910prod" (jruby-protocol/get-environment-class-info-tag
                                  service
                                  "production"))))
             (let [production-cache-id-before-marked-expired
                   (jruby-protocol/get-environment-class-info-cache-generation-id!
                    service
                    "production")]
               (jruby-protocol/mark-all-environments-expired! service)
               (is (nil? (jruby-protocol/get-environment-class-info-tag
                          service
                          "production")))
               (is (not= production-cache-id-before-marked-expired
                         (jruby-protocol/get-environment-class-info-cache-generation-id!
                          service
                          "production")))
               (is (nil? (jruby-protocol/get-environment-class-info-tag service
                                                                        "test")))
               (is (not= test-cache-id-after-first-update
                         (jruby-protocol/get-environment-class-info-cache-generation-id!
                          service
                          "test")))))
           (testing (str "when all environments expired between get and set "
                         "for environment that did not previously exist")
             (let [staging-cache-id-before-marked-expired
                   (jruby-protocol/get-environment-class-info-cache-generation-id!
                    service
                    "staging")
                   _ (jruby-protocol/mark-all-environments-expired! service)
                   staging-cache-id-after-marked-expired
                   (jruby-protocol/get-environment-class-info-cache-generation-id!
                    service
                    "staging")]
               (jruby-protocol/set-environment-class-info-tag!
                service
                "staging"
                "1234staging"
                staging-cache-id-before-marked-expired)
               (is (= nil (jruby-protocol/get-environment-class-info-tag
                           service
                           "staging"))
                   (str "Tag should not have been changed by the prior set "
                        "since the environment was marked expired after the "
                        "`staging-cache-id-before-marked-expired` was read"))
               (is (= staging-cache-id-after-marked-expired
                      (jruby-protocol/get-environment-class-info-cache-generation-id!
                       service
                       "staging"))
                   (str "Cache id should not have been changed by the prior "
                        "set since the environment was marked expired after the "
                        "`staging-cache-id-before-marked-expired` was "
                        "read"))))))))))
