(ns puppetlabs.puppetserver.ruby.http-client-test
  (:import (org.jruby.embed LocalContextScope LocalVariableBehavior
                            ScriptingContainer))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.webserver.common :refer [http-get]]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]))

(defn ring-app
  [req]
  {:status 200
   :body "hi"})

(def scripting-container
  "A JRuby ScriptingContainer with LocalVariableBehavior.PERSISTENT
  so that local variables will persistent across calls to 'runScriptlet'."
  (-> (ScriptingContainer. LocalContextScope/SINGLETHREAD
                           LocalVariableBehavior/PERSISTENT)
      (jruby-puppet/prep-scripting-container jruby-testutils/ruby-load-path
                                             jruby-testutils/gem-home)))

(deftest test-ruby-http-client
  (jetty9/with-test-webserver ring-app port
    (.runScriptlet
      scripting-container
      (str "require 'puppet/server/http_client';"
           (format "c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => false});"
                   port)))

    (testing "HTTP GET"
      (is (= "hi" (.runScriptlet scripting-container "c.get('/', {}).body"))))

    (testing "HTTP POST"
      (is (= "hi" (.runScriptlet scripting-container "c.post('/', 'foo', {}).body"))))))