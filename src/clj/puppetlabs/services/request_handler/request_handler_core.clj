(ns puppetlabs.services.request-handler.request-handler-core
  (:import (java.security.cert X509Certificate)
           (java.util HashMap)
           (java.io StringReader)
           (com.puppetlabs.puppetserver JRubyPuppetResponse))
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.certificate-authority.core :as ssl]
            [ring.middleware.params :as ring-params]
            [ring.middleware.nested-params :as ring-nested-params]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defn unmunge-http-header-name
  [setting]
  "Given the value of a Puppet setting which contains a munged HTTP header name,
  convert it to the actual header name in all lower-case."
  (->> (string/split setting #"_")
       rest
       (string/join "-")
       string/lower-case))

(defn config->request-handler-settings
  "Given an entire Puppet Server configuration map, return only those keys
  which are required by the request handler service."
  [{:keys [puppet-server master]}]
  {:allow-header-cert-info   (true? (:allow-header-cert-info master))
   :ssl-client-verify-header (unmunge-http-header-name
                               (:ssl-client-verify-header puppet-server))
   :ssl-client-header        (unmunge-http-header-name
                               (:ssl-client-header puppet-server))})

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
          ring-params/params-request)))

(defn as-jruby-request
  "Given a ring HTTP request, return a new map that contains all of the data
   needed by the ruby HTTP layer to process it.  This function does a couple
   things that are a bit weird:
      * It reads the entire request body into memory.  This is not ideal for
        performance and memory usage, but we have to ship this thing over to
        JRuby, so I don't think there's any way around this.
      * It also extracts the name of the SSL client cert and includes that
        in the map it returns, because it's needed by the ruby layer. It is
        possible that the HTTPS termination has happened external to Puppet
        Server, if so then the DN will be provided by user-specified HTTP
        header as well as the authentication status of the CN, and no
        certificate will be available."
  [config request]
  (let [headers     (:headers request)
        jruby-req   {:uri            (:uri request)
                     :params         (:params request)
                     :remote-addr    (:remote-addr request)
                     :headers        headers
                     :body           (:body-string request)
                     :request-method (-> (:request-method request)
                                         name
                                         string/upper-case)}
        header-dn   (get headers (:ssl-client-header config))
        header-auth (get headers (:ssl-client-verify-header config))]
    (when (and header-dn (not (:allow-header-cert-info config)))
      (log/warn "The HTTP header " (:ssl-client-header config) " was specified,"
                "but the Puppet Server global config option allow-header-cert-info"
                "was either not set, or was set to false. This header will be ignored."))
    (if (and (:allow-header-cert-info config) header-dn)
      (try
        (conj jruby-req {:client-cert-cn (ssl/x500-name->CN header-dn)
                         :client-cert    nil
                         :authenticated  (= "SUCCESS" header-auth)})
        (catch AssertionError _
          (conj jruby-req {:client-cert-cn nil
                           :client-cert    nil
                           :authenticated  false})))
      (let [cert (:ssl-client-cert request)
            cn (get-cert-common-name request)]
        (conj jruby-req {:client-cert    cert
                         :client-cert-cn cn
                         :authenticated  (not (nil? cn))})))))

(defn make-request-mutable
  [request]
  "Make the request mutable.  This is required by the ruby layer."
  (HashMap. request))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn handle-request
  [request jruby-instance config]
  (->> request
       wrap-params-for-jruby
       (as-jruby-request config)
       clojure.walk/stringify-keys
       make-request-mutable
       (.handleRequest jruby-instance)
       response->map))
