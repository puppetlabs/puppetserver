(ns puppetlabs.master.services.handler.request-handler-core
  (:import (com.puppetlabs.master JRubyPuppet
                                  JRubyPuppetResponse)
           (java.security.cert X509Certificate)
           (java.util HashMap)
           (java.io StringReader))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [puppetlabs.kitchensink.core :as ks]
            [ring.middleware.params :as ring-params]
            [ring.middleware.nested-params :as ring-nested-params]))

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

(defn wrap-params-for-jruby
  "Pull parameters from the URL query string and/or urlencoded form POST
   body into the ring request map.  Includes some special processing for
   a request destined for JRubyPuppet."
  [request]
  ; Need to slurp the request body before invoking any of the ring
  ; middleware functions because a handle to the body payload needs to be passed
  ; through to JRubyPuppet and the body won't be around to slurp if any of
  ; the ring functions happen to slurp it up first.  This would happen for a
  ; 'application/x-www-form-urlencoded' form post where ring needs to slurp
  ; in the body of the request in order to parse out parameters from the form.

  ; Arguably could use "ISO-8859-1" as the default encoding if one is not
  ; specified on the request, but "UTF-8" is what ring would use as well.
  (let [body-string (slurp (:body request)
                           :encoding (or (:character-encoding request)
                                         "UTF-8"))]
        (->
          request
          ; Leave the slurped content under an alternate key so that it
          ; is available to be proxied on to the JRubyPuppet request.
          (assoc :body-string body-string)
          ; Body content has been slurped already so wrap it in a new reader
          ; so that a copy of it can be obtained by ring middleware functions,
          ; if needed.
          (assoc :body (StringReader. body-string))
          ; Compojure request may have destructured parameters from subportions
          ; of the URL into the params map by this point.  Clear this out
          ; before invoking the ring middleware param functions so that keys
          ; pulled from the query string or form body parameters don't
          ; inadvertently conflict.
          (assoc :params {})
          ; Defer to ring middleware to pull out parameters from the query
          ; string and/or form body.
          ring-params/params-request
          ; Defer to ring middleware to pull any array-like parameters into
          ; vector values, e.g., a query string of 'arr[]=one&arr[]=two' would
          ; turn into '"arr" -> ["one" "two"]'.
          ring-nested-params/nested-params-request)))

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
    :params         (:params request)
    :remote-addr    (:remote-addr request)
    :headers        (:headers request)
    :body           (:body-string request)
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
       wrap-params-for-jruby
       as-jruby-request
       clojure.walk/stringify-keys
       make-request-mutable
       (.handleRequest jruby-instance)
       response->map))
