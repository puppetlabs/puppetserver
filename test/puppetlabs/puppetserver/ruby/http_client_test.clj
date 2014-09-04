(ns puppetlabs.puppetserver.ruby.http-client-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.webserver.common :refer [http-get]]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]))

(defn ring-app
  [req]
  {:status 200
   :body "hi"})

(deftest test-ruby-http-client
  (jetty9/with-test-webserver ring-app port
    (let [sc (jruby-puppet/empty-scripting-container
               ["./ruby/puppet/lib" "./ruby/facter/lib"]
               "./scratch/jruby-gems")]
      (.runScriptlet sc "require 'puppet/server/http_client'")
      (is (= "hi" (.runScriptlet
                    sc
                    (format
                      (str "c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => false});\n"
                           "c.get('/', {}).body")
                      port)))))))