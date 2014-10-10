(ns puppetlabs.puppetserver.ruby.http-client-test
  (:import (org.jruby.embed LocalContextScope LocalVariableBehavior
                            ScriptingContainer))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.webserver.common :refer [http-get]]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]
            [ring.middleware.basic-authentication :as auth]))

(defn ring-app
  [req]
  {:status 200
   :body "hi"})

(defn authenticated? [name pass]
  (and (= name "foo")
       (= pass "bar")))

(def ring-app-with-auth
  (-> ring-app
      (auth/wrap-basic-authentication authenticated?)))

(defn create-scripting-container
  "A JRuby ScriptingContainer with an instance of 'Puppet::Server::HttpClient'
  assigned to a variable 'c'.  The ScriptingContainer was created
  with LocalVariableBehavior.PERSISTENT so that the HTTP client instance
  will persistent across calls to 'runScriptlet' in tests."
  [port]
  (doto (ScriptingContainer. LocalContextScope/SINGLETHREAD
                             LocalVariableBehavior/PERSISTENT)
    (jruby-puppet/prep-scripting-container jruby-testutils/ruby-load-path
                                           jruby-testutils/gem-home)
    (.runScriptlet (str "require 'puppet/server/http_client';"
                        (format "c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => false});"
                                port)))))

(deftest test-ruby-http-client
  (jetty9/with-test-webserver ring-app port
    (let [scripting-container (create-scripting-container port)]
      (testing "HTTP GET"
        (is (= "hi" (.runScriptlet scripting-container "c.get('/', {}).body"))))

      (testing "HTTP POST"
        (is (= "hi" (.runScriptlet scripting-container "c.post('/', 'foo', {}).body")))))))

(deftest http-basic-auth
  (jetty9/with-test-webserver ring-app-with-auth port
    (let [scripting-container (create-scripting-container port)]
      (testing "no credentials"
        (.runScriptlet scripting-container "response = c.post('/', 'foo', {})")
        (is (= "Net::HTTPUnauthorized" (.runScriptlet scripting-container "response.class.to_s")))
        (is (= "access denied" (.runScriptlet scripting-container "response.body"))))

      (testing "valid credentials"
        (let [auth "{ :basic_auth => { :user => 'foo', :password => 'bar' }}"]
          (.runScriptlet scripting-container (format "response = c.post('/', 'foo', {}, %s)" auth)))
        (is (= "Net::HTTPOK" (.runScriptlet scripting-container "response.class.to_s")))
        (is (= "hi" (.runScriptlet scripting-container "response.body"))))

      (testing "invvalid credentials"
        (let [auth "{ :basic_auth => { :user => 'foo', :password => 'baz' }}"]
          (.runScriptlet scripting-container (format "response = c.post('/', 'foo', {}, %s)" auth)))
        (is (= "Net::HTTPUnauthorized" (.runScriptlet scripting-container "response.class.to_s")))
        (is (= "access denied" (.runScriptlet scripting-container "response.body")))))))
