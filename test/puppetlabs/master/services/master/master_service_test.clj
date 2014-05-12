(ns puppetlabs.master.services.master.master-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.master.services.master.master-service :refer :all]
    [puppetlabs.master.services.config.jvm-puppet-config-service :refer [jvm-puppet-config-service]]
    [puppetlabs.master.services.jruby.jruby-puppet-service :as jruby]
    [puppetlabs.master.services.protocols.jruby-puppet :as jruby-protocol]
    [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
    [puppetlabs.master.services.handler.request-handler-service :refer [request-handler-service]]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
    [puppetlabs.master.services.jruby.testutils :as jruby-testutils]
    [me.raynes.fs :as fs]))

(def test-dir "target/master-service-test")

(deftest ca-files-test
  (testing (str "CA settings from puppet are honored and the CA files are "
                "created when the service starts up.")
    (try
      (fs/mkdir test-dir)
      (let [services [master-service
                      jvm-puppet-config-service
                      jruby/jruby-puppet-pooled-service
                      jetty9-service
                      request-handler-service]
            config {:jruby-puppet (assoc jruby-testutils/default-config-no-size
                                    :master-conf-dir
                                    "test-resources/another-conf-var-root/conf")
                    :webserver    {:port 8081}}]
        (tk-testutils/with-app-with-config
          app
          services
          config

          (let [jruby-service (tk-app/get-service app :JRubyPuppetService)]
            (jruby/with-jruby-puppet
              jruby-puppet
              jruby-service
              (jruby-protocol/get-default-pool-descriptor jruby-service)

              (letfn [(test-path!
                        [setting expected-path]
                        (is (= (fs/absolute-path expected-path)
                               (.getSetting jruby-puppet setting)))
                        (is (fs/exists? (fs/absolute-path expected-path))))]

                (test-path! "capub" "target/master-service-test/ca/public/key")
                (test-path! "cakey" "target/master-service-test/ca/private/key")
                (test-path! "cacert" "target/master-service-test/ca/cert")
                (test-path! "localcacert" "target/master-service-test/ca/dir")
                (test-path! "cacrl" "target/master-service-test/crl")
                (test-path! "hostpubkey" "target/master-service-test/master/pub/key")
                (test-path! "hostprivkey" "target/master-service-test/master/private/key")
                (test-path! "hostcert" "target/master-service-test/master/cert"))))))
      (finally
        (fs/delete-dir test-dir)))))
