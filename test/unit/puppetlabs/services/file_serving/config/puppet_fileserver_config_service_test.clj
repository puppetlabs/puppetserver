(ns puppetlabs.services.file-serving.config.puppet-fileserver-config-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.puppet-fileserver-config :refer :all]
            [puppetlabs.services.file-serving.config.puppet-fileserver-config-service :refer :all]
            [puppetlabs.services.config.puppet-server-config-service :refer :all]
            [puppetlabs.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]))

(def service-and-deps
  [puppet-fileserver-config-service puppet-server-config-service jruby-puppet-pooled-service jetty9-service
   profiler/puppet-profiler-service])

(def required-config
  (merge (jruby-testutils/jruby-puppet-tk-config
           (jruby-testutils/jruby-puppet-config {:max-active-instances 1}))
         {:webserver {:port 8081}}))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/file_serving/config")

(deftest fileserver-config-service-functions
  (tk-testutils/with-app-with-config
    app
    service-and-deps
    (-> required-config
        (assoc-in [:jruby-puppet :master-conf-dir] (str test-resources-dir "/master/conf")))
    (testing "Basic puppet file-server config service function usage"

      (let [service (tk-app/get-service app :PuppetFileserverConfigService)
            [mount path] (find-mount service "files1/path/to/file")]
        (is (= {:path "/etc/puppet/files1" :acl [:allow "127.0.0.1/32" :allow "192.168.10.0/24" :allow "this-certname-only.domain.com"]}
               mount))
        (is (= "path/to/file"
               path))))))

