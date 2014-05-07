(ns puppetlabs.master.services.handler.request-handler-core
  (:import (com.puppetlabs.master JRubyPuppet
                                  JRubyPuppetResponse)
           (java.security.cert X509Certificate)
           (java.util HashMap))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.walk :as walk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn get-cert-name
  "Given a request, return the name of the SSL client certificate."
  [request]
  (let [cert (:ssl-client-cert request)]
    (assert (instance? X509Certificate cert))
    (-> cert .getSubjectX500Principal .getName)))

(defn response->map
  "Converts a JRubyPuppetResponse instance to a map."
  [response]
  { :pre [(instance? JRubyPuppetResponse response)]
    :post [(map? %)] }
  (let [body (slurp (.getBody response))]
    { :status   (.getStatus response)
      :body     body
      :headers  { "Content-Type"      (.getContentType response)
                  "Content-Length"    (str (count body))
                  "X-Puppet-Version"  (.getPuppetVersion response)} }))

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
  { :uri              (:uri request)
    :params           (:params request)
    :headers          (:headers request)
    :body             (slurp (:body request))
    :client-cert-name (get-cert-name request)
    :request-method   (->
                        (:request-method request)
                        name
                        string/upper-case) })

(defn make-maps-mutable
  [request]
  "Make the request and its parameters mutable.
  This is required by the ruby layer."
  (HashMap. (update-in request ["params"] #(HashMap. %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn handle-request
  [request jruby-instance]
  (->> request
       as-jruby-request
       clojure.walk/stringify-keys
       make-maps-mutable
       (.handleRequest jruby-instance)
       response->map))
