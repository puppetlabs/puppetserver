(ns puppetlabs.master.services.handler.request-handler-core
  (:import (com.puppetlabs.master JRubyPuppet
                                  JRubyPuppetResponse)
           (java.security.cert X509Certificate)
           (java.util HashMap))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn get-cert-common-name
  "Given a request, return the Common Name from the client certificate subject."
  [request]
  (if-let [cert (:ssl-client-cert request)]
    (if-let [cert-dn (-> cert .getSubjectX500Principal .getName)]
      (if-let [cert-cn (ks/cn-for-dn cert-dn)]
        cert-cn
        (log/errorf "cn not found in client certificate dn: %s"
                   cert-dn))
      (log/error "dn not found for client certificate subject"))))

(defn response->map
  "Converts a JRubyPuppetResponse instance to a map."
  [response]
  { :pre [(instance? JRubyPuppetResponse response)]
    :post [(map? %)] }
    { :status  (.getStatus response)
      :body    (.getBody response)
      :headers {"Content-Type"     (.getContentType response)
                "X-Puppet-Version" (.getPuppetVersion response)}})

(defn as-jruby-request
  "Given a ring HTTP request, return a new map that contains all of the data
   needed by the ruby HTTP layer to process it.  This function does a couple
   things that are a bit weird:
      * It reads the entire request body into memory.  This is not ideal for
        performance and memory usage, but we have to ship this thing over to
        JRuby, so I don't think there's any way around this.
      * It also extracts the name of the SSL client cert and includes that
        in the map it returns, because it's needed by the ruby layer."
  [request]
  { :uri            (:uri request)
    :query          (:query-params request)
    :peeraddr       (:remote-addr request)
    :headers        (:headers request)
    :body           (slurp (:body request))
    :client-cert-cn (get-cert-common-name request)
    :request-method (->
                      (:request-method request)
                      name
                      string/upper-case)})

(defn make-request-mutable
  [request]
  "Make the request mutable.  This is required by the ruby layer."
  (HashMap. request))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn handle-request
  [request jruby-instance]
  (->> request
       as-jruby-request
       clojure.walk/stringify-keys
       make-request-mutable
       (.handleRequest jruby-instance)
       response->map))
