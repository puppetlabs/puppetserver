(ns puppetlabs.puppetserver.ruby.http-client-test
  (:import (org.jruby.embed LocalContextScope LocalVariableBehavior
                            ScriptingContainer EvalFailedException)
           (org.apache.http ConnectionClosedException)
           (com.puppetlabs.http.client HttpClientException)
           (javax.net.ssl SSLHandshakeException)
           (java.util HashMap)
           (java.io IOException))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.trapperkeeper.testutils.webserver.common :refer [http-get]]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [ring.middleware.basic-authentication :as auth]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]))

;; NOTE: this namespace is pretty disgusting.  It'd be much nicer to test this
;; ruby code via ruby spec tests, but since we need to stand up a webserver to
;; make requests against, it's way easier to do it with clojure and tk-j9.

(defn test-resource
  [filename]
  (str "./dev-resources/puppetlabs/puppetserver/ruby/http_client_test/" filename))

(defn ring-app
  [req]
  {:status 200
   :body "hi"})

(defn ring-app-alternate
  [req]
  {:status 200
   :body "bye"})

(defn ring-app-connection-closed
  [req]
  {:status 200
   :body (str "The Connection header has value "
              ((:headers req) "connection"))})

(defn authenticated? [name pass]
  (and (= name "foo")
       (= pass "bar")))

(def ring-app-with-auth
  (-> ring-app
      (auth/wrap-basic-authentication authenticated?)))

(defn ssl-connection-exception?
  [ex]
  (or
    (instance? ConnectionClosedException ex)
    (instance? SSLHandshakeException ex)
    (and (instance? IOException ex)
         (= "Connection reset by peer" (.getMessage ex)))))

(defn get-http-client-settings
  [options]
  (let [result (HashMap.)]
    (if (contains? options :ssl-protocols)
      (.put result "ssl_protocols" (into-array String (:ssl-protocols options))))
    (if (contains? options :cipher-suites)
      (.put result "cipher_suites" (into-array String (:cipher-suites options))))
    result))

(def ca-pem (test-resource "ca.pem"))
(def cert-pem (test-resource "localhost_cert.pem"))
(def privkey-pem (test-resource "localhost_key.pem"))

(defn create-scripting-container
  "A JRuby ScriptingContainer with an instance of 'Puppet::Server::HttpClient'
  assigned to a variable 'c'.  The ScriptingContainer was created
  with LocalVariableBehavior.PERSISTENT so that the HTTP client instance
  will persistent across calls to 'runScriptlet' in tests."
  ([port]
   (create-scripting-container port {:use-ssl false}))
  ([port options]
   (let [use-ssl?             (true? (:use-ssl options))
         http-client-settings (get-http-client-settings
                                (select-keys options [:ssl-protocols :cipher-suites]))
         sc                   (ScriptingContainer. LocalContextScope/SINGLETHREAD
                                                   LocalVariableBehavior/PERSISTENT)]
     (jruby-internal/init-jruby-config sc
                                       jruby-testutils/ruby-load-path
                                       jruby-testutils/gem-home)
     (.runScriptlet sc "require 'puppet/server/http_client'")
     (let [http-client-class (.runScriptlet sc "Puppet::Server::HttpClient")]
       (.callMethod sc http-client-class "initialize_settings" http-client-settings Object))
     (.runScriptlet sc (str "require 'puppet/server/http_client';"
                            (format "c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                                    port use-ssl?)))
     (doto sc
       (.runScriptlet (format "Puppet[:hostcert] = '%s'" cert-pem))
       (.runScriptlet (format "Puppet[:hostprivkey] = '%s'" privkey-pem))
       (.runScriptlet (format "Puppet[:localcacert] = '%s'" ca-pem)))
     sc)))

(defn terminate-scripting-container
  [scripting-container]
  (.runScriptlet scripting-container "Puppet::Server::HttpClient.terminate")
  (.terminate scripting-container))

(deftest test-ruby-http-client
  (jetty9/with-test-webserver ring-app port
    (let [scripting-container (create-scripting-container port)]
      (testing "HTTP GET"
        (is (= "hi" (.runScriptlet scripting-container "c.get('/', {}).body"))))

      (testing "HTTP POST"
        (is (= "hi" (.runScriptlet scripting-container "c.post('/', 'foo', {}).body"))))

      (terminate-scripting-container scripting-container))))

(deftest http-basic-auth
  (jetty9/with-test-webserver ring-app-with-auth port
    (let [scripting-container (create-scripting-container port)]
      (testing "no credentials"
        (.runScriptlet scripting-container "response = c.post('/', 'foo', {})")
        (is (= "401" (.runScriptlet scripting-container "response.code")))
        (is (= "access denied" (.runScriptlet scripting-container "response.body"))))

      (testing "valid credentials"
        (let [auth "{ :basic_auth => { :user => 'foo', :password => 'bar' }}"]
          (.runScriptlet scripting-container (format "response = c.post('/', 'foo', {}, %s)" auth)))
        (is (= "200" (.runScriptlet scripting-container "response.code")))
        (is (= "hi" (.runScriptlet scripting-container "response.body"))))

      (testing "invalid credentials"
        (let [auth "{ :basic_auth => { :user => 'foo', :password => 'baz' }}"]
          (.runScriptlet scripting-container (format "response = c.post('/', 'foo', {}, %s)" auth)))
        (is (= "401" (.runScriptlet scripting-container "response.code")))
        (is (= "access denied" (.runScriptlet scripting-container "response.body"))))

      (terminate-scripting-container scripting-container))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SSL Tests

(defmacro with-webserver-with-protocols
  [protocols cipher-suites & body]
  `(jetty9/with-test-webserver-and-config ring-app port#
    (merge {:ssl-host    "localhost"
            :ssl-port    10080
            :ssl-ca-cert ca-pem
            :ssl-cert    cert-pem
            :ssl-key     privkey-pem}
           (if ~protocols
             {:ssl-protocols ~protocols})
           (if ~cipher-suites
             {:cipher-suites ~cipher-suites}))
    ~@body))

(defn raise-caught-http-error
  "Returns a Ruby script string that executes `script', rescues the expected
   Puppet::Server::HttpClientError, and raises the nested exception for testing
   the underlying cause."
  [script]
  (str "begin;"
       script
       ";rescue Puppet::Server::HttpClientError => e;"
       "  raise e.cause.getCause;"
       "end"))

(deftest https-tls-defaults
  (testing "requests fail without an SSL client"
    (with-webserver-with-protocols nil nil
      (let [sc (create-scripting-container 10080)]
        (logutils/with-test-logging
          (try
            (.runScriptlet sc (raise-caught-http-error "c.get('/', {})"))
            (is false "Expected HTTP connection to HTTPS port to fail")
           (catch EvalFailedException e
             (is (instance? ConnectionClosedException (.getCause e))))))
        (terminate-scripting-container sc))))

  (testing "Can connect via TLSv1 by default"
    (with-webserver-with-protocols ["TLSv1"] nil
       (let [sc (create-scripting-container 10080 {:use-ssl true})]
         (.runScriptlet sc "response = c.get('/', {})")
         (is (= "200" (.runScriptlet sc "response.code")))
         (is (= "hi" (.runScriptlet sc "response.body")))
         (terminate-scripting-container sc))))

  (testing "Can connect via TLSv1.1 by default"
    (with-webserver-with-protocols ["TLSv1.1"] nil
       (let [sc (create-scripting-container 10080 {:use-ssl true})]
         (.runScriptlet sc "response = c.get('/', {})")
         (is (= "200" (.runScriptlet sc "response.code")))
         (is (= "hi" (.runScriptlet sc "response.body")))
         (terminate-scripting-container sc))))

  (testing "Can connect via TLSv1.2 by default"
    (with-webserver-with-protocols ["TLSv1.2"] nil
       (let [sc (create-scripting-container 10080 {:use-ssl true})]
         (.runScriptlet sc "response = c.get('/', {})")
         (is (= "200" (.runScriptlet sc "response.code")))
         (is (= "hi" (.runScriptlet sc "response.body")))
         (terminate-scripting-container sc)))))

(deftest https-sslv3
  (logutils/with-test-logging
    (with-webserver-with-protocols ["SSLv3"] nil
      (testing "Cannot connect via SSLv3 by default"
        (let [sc (create-scripting-container 10080 {:use-ssl true})]
          (try
            (.runScriptlet sc (raise-caught-http-error "c.get('/', {})"))
            (is false "Expected HTTP connection to HTTPS port to fail")
            (catch EvalFailedException e
              (is (ssl-connection-exception? (.. e getCause getCause)))))
          (terminate-scripting-container sc)))

      (testing "Can connect via SSLv3 when specified"
        (let [sc (create-scripting-container
                  10080
                  {:ssl-protocols ["SSLv3" "TLSv1"]
                   :use-ssl true})]
          (.runScriptlet sc "response = c.get('/', {})")
          (is (= "200" (.runScriptlet sc "response.code")))
          (is (= "hi" (.runScriptlet sc "response.body")))
          (terminate-scripting-container sc))))))

(deftest https-cipher-suites
  (logutils/with-test-logging
    (with-webserver-with-protocols ["SSLv3"] ["SSL_RSA_WITH_RC4_128_SHA"]
      (testing "Should not be able to connect if no matching ciphers"
        (let [sc (create-scripting-container
                  10080
                  {:ssl-protocols ["SSLv3"]
                   :cipher-suites ["SSL_RSA_WITH_RC4_128_MD5"]
                   :use-ssl true})]
          (try
            (.runScriptlet sc (raise-caught-http-error "c.get('/', {})"))
            (is false "Expected HTTP connection to HTTPS port to fail")
            (catch EvalFailedException e
              (is (ssl-connection-exception? (.. e getCause)))))
          (terminate-scripting-container sc)))

      (testing "Should be able to connect if explicit matching ciphers are configured"
        (let [sc (create-scripting-container
                  10080
                  {:ssl-protocols ["SSLv3"]
                   :cipher-suites ["SSL_RSA_WITH_RC4_128_SHA"]
                   :use-ssl true})]
          (.runScriptlet sc "response = c.get('/', {})")
          (is (= "200" (.runScriptlet sc "response.code")))
          (is (= "hi" (.runScriptlet sc "response.body")))
          (terminate-scripting-container sc))))))

(deftest clients-persist
  (testing "client persists when making HTTP requests"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app port
        (let [sc (create-scripting-container port)
              client1 (.runScriptlet sc "c.get('/', {}); c.class.client")
              client2 (.runScriptlet sc "c.post('/', 'foo', {}); c.class.client")]
          (is (= client1 client2))
          (terminate-scripting-container sc)))))
  (testing "all instances of HttpClient have the same underlying client object"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app port
        (let [sc (create-scripting-container port)
              client1 (.runScriptlet sc "c.class.client")
              client2 (.runScriptlet sc (str (format "c2 = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                                                  port false)
                                          "c2.class.client"))]
          (is (= client1 client2))
          (terminate-scripting-container sc))))))

(deftest connections-closed
  (testing "connection header always set to close on get"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-connection-closed port
        (let [sc (create-scripting-container port)]
          (is (= "The Connection header has value close" (.runScriptlet sc "c.get('/', {}).body")))
          (terminate-scripting-container sc)))))
  (testing "connection header always set to close on post"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-connection-closed port
        (let [sc (create-scripting-container port)]
          (is (= "The Connection header has value close" (.runScriptlet sc "c.post('/', 'foo', {}).body")))
          (terminate-scripting-container sc)))))
  (testing "client's terminate function closes the client"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-connection-closed port
        (let [sc (create-scripting-container port)]
          (.runScriptlet sc "response = c.get('/', {})")
          (is (= "200" (.runScriptlet sc "response.code")))
          (.runScriptlet sc "c.class.terminate")
          (try
            (.runScriptlet sc "response = c.get('/', {})")
            (catch EvalFailedException e
              (let [wrapped-exception (.getCause e)
                    message           (.getMessage e)]
                (is (instance? IllegalStateException wrapped-exception))
                (is (re-find #"Request cannot be executed; I/O reactor status: STOPPED" message)))))
          (.terminate sc))))))


(deftest http-and-https
  (testing "can make http calls after https calls without a new scripting container"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-alternate port
        (with-webserver-with-protocols nil nil
          (let [sc (create-scripting-container 10080 {:use-ssl true})]
            (.runScriptlet sc "response = c.get('/', {})")
            (is (= "200" (.runScriptlet sc "response.code")))
            (is (= "hi" (.runScriptlet sc "response.body")))
            (.runScriptlet sc (str (format "c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                                           port false)
                                   "response = c.get('/', {})"))
            (is (= "200" (.runScriptlet sc "response.code")))
            (is (= "bye" (.runScriptlet sc "response.body")))
            (terminate-scripting-container sc))))))

  (testing "can make https calls after http calls without a new scripting container"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-alternate port
        (with-webserver-with-protocols nil nil
          (let [sc (create-scripting-container port)]
            (.runScriptlet sc "response = c.get('/', {})")
            (is (= "200" (.runScriptlet sc "response.code")))
            (is (= "bye" (.runScriptlet sc "response.body")))
            (.runScriptlet sc (str (format "c = Puppet::Server::HttpClient.new('localhost', %d, {:use_ssl => %s});"
                                           10080 true)
                                   "response = c.get('/', {})"))
            (is (= "200" (.runScriptlet sc "response.code")))
            (is (= "hi" (.runScriptlet sc "response.body")))
            (terminate-scripting-container sc)))))))

