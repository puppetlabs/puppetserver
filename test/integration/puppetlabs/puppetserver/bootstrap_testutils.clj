(ns puppetlabs.puppetserver.bootstrap-testutils
  (:require [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [puppetlabs.ssl-utils.simple :as ssl-simple]
            [schema.core :as schema]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.jruby.jruby-puppet-testutils
             :as jruby-puppet-testutils])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (javax.net.ssl SSLContext)))

(def dev-config-file
  "./dev/puppetserver.conf")

(def dev-bootstrap-file
  "./dev/bootstrap.cfg")

(def logging-test-conf-file
  "./dev-resources/logback-test.xml")

(def server-conf-dir
  "./target/server-conf")

(def server-code-dir
  "./target/server-code")

(def server-var-dir
  "./target/server-var")

(def server-run-dir
  "./target/server-var/run")

(def server-log-dir
  "./target/server-var/log")

(def multithreaded
  (= "true" (System/getenv "MULTITHREADED")))

(defn load-dev-config-with-overrides
  [overrides]
  (let [tmp-conf (ks/temp-file "puppetserver" ".conf")]
    (fs/copy dev-config-file tmp-conf)
    (-> (tk-config/load-config (.getPath tmp-conf))
        (assoc-in [:global :logging-config] logging-test-conf-file)
        (assoc-in [:jruby-puppet :server-conf-dir] server-conf-dir)
        (assoc-in [:jruby-puppet :server-code-dir] server-code-dir)
        (assoc-in [:jruby-puppet :server-var-dir] server-var-dir)
        (assoc-in [:jruby-puppet :server-run-dir] server-run-dir)
        (assoc-in [:jruby-puppet :server-log-dir] server-log-dir)
        (assoc-in [:jruby-puppet :multithreaded] multithreaded)
        (ks/deep-merge overrides))))

(defn services-from-dev-bootstrap
  ([] (services-from-dev-bootstrap dev-bootstrap-file))
  ([bootstrap-config-file]
   (tk-bootstrap/parse-bootstrap-config! bootstrap-config-file)))

(defn services-from-dev-bootstrap-plus-mock-jruby-pool-manager-service
  ([config]
   (jruby-puppet-testutils/add-mock-jruby-pool-manager-service
    (services-from-dev-bootstrap)
    config))
  ([config mock-jruby-puppet-fn]
   (jruby-puppet-testutils/add-mock-jruby-pool-manager-service
    (services-from-dev-bootstrap)
    config
    mock-jruby-puppet-fn)))

(defmacro with-puppetserver-running-with-services
  [app services config-overrides & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(tk-testutils/with-app-with-config
       ~app
       ~services
       ~config
       ~@body)))

(defmacro with-puppetserver-running-with-services-and-mock-jrubies
  "This macro should be used with caution; it makes tests run much more quickly,
  but you should be careful to make sure that the mocking won't be subverting
  any important test coverage.  For this reason, we require a `docstring` argument
  to be passed in, as a sort of annotation explaining why you feel it's safe to
  use this mocking in your test."
  [_docstring app services config-overrides & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(let [services# (conj ~services
                           (jruby-puppet-testutils/mock-jruby-pool-manager-service
                            ~config))]
       (tk-testutils/with-app-with-config
        ~app
        services#
        ~config
        ~@body))))

(defmacro with-puppetserver-running-with-services-and-mock-jruby-puppet-fn
  "This macro should be used with caution; it makes tests run much more quickly,
  but you should be careful to make sure that the mocking won't be subverting
  any important test coverage.  For this reason, we require a `docstring` argument
  to be passed in, as a sort of annotation explaining why you feel it's safe to
  use this mocking in your test."
  [_docstring app services config-overrides mock-jruby-puppet-fn & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(let [services# (conj ~services
                           (jruby-puppet-testutils/mock-jruby-pool-manager-service
                            ~config
                            ~mock-jruby-puppet-fn))]
       (tk-testutils/with-app-with-config
        ~app
        services#
        ~config
        ~@body))))

(defmacro with-puppetserver-running-with-config
  [app config & body]
  `(let [services# (tk-bootstrap/parse-bootstrap-config! ~dev-bootstrap-file)]
     (tk-testutils/with-app-with-config
      ~app
      services#
      ~config
      ~@body)))

(defmacro with-puppetserver-running
  [app config-overrides & body]
  `(let [config# (load-dev-config-with-overrides ~config-overrides)]
     (let [services# (tk-bootstrap/parse-bootstrap-config! ~dev-bootstrap-file)]
       (tk-testutils/with-app-with-config
        ~app
        services#
        config#
        ~@body))))

(defmacro with-puppetserver-running-with-mock-jrubies
  "This macro should be used with caution; it makes tests run much more quickly,
  but you should be careful to make sure that the mocking won't be subverting
  any important test coverage.  For this reason, we require a `docstring` argument
  to be passed in, as a sort of annotation explaining why you feel it's safe to
  use this mocking in your test."
  [_docstring app config-overrides & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(let [services#
           (services-from-dev-bootstrap-plus-mock-jruby-pool-manager-service
            ~config)]
       (tk-testutils/with-app-with-config
        ~app
        services#
        ~config
        ~@body))))

(defmacro with-puppetserver-running-with-mock-jruby-puppet-fn
  "This macro should be used with caution; it makes tests run much more quickly,
  but you should be careful to make sure that the mocking won't be subverting
  any important test coverage.  For this reason, we require a `docstring` argument
  to be passed in, as a sort of annotation explaining why you feel it's safe to
  use this mocking in your test."
  [_docstring app config-overrides mock-jruby-puppet-fn & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(let [services#
           (services-from-dev-bootstrap-plus-mock-jruby-pool-manager-service
            ~config
            ~mock-jruby-puppet-fn)]
       (tk-testutils/with-app-with-config
        ~app
        services#
        ~config
        ~@body))))

(defn write-to-stream [o]
  (let [s (ByteArrayOutputStream.)]
    (ssl-utils/obj->pem! o s)
    (-> s .toByteArray ByteArrayInputStream.)))

(schema/defn get-ca-cert-for-running-server :- ca/Certificate
  []
  (ssl-utils/pem->cert "./target/server-conf/ca/ca_crt.pem"))

(schema/defn get-cert-signed-by-ca-for-running-server
  :- (schema/pred ssl-simple/ssl-cert?)
  [ca-cert :- ca/Certificate
   certname :- schema/Str]
  (let [ca-private-key (ssl-utils/pem->private-key
                        (str "./target/server-conf/ca/ca_key.pem"))
        ca-dn (-> ca-cert
                  (.getSubjectX500Principal)
                  (.getName))
        ca-map {:cert ca-cert
                :public-key (.getPublicKey ca-cert)
                :private-key ca-private-key
                :x500-name (-> (.getSubjectX500Principal ca-cert)
                               (.getName))
                :certname (ssl-utils/x500-name->CN ca-dn)}]
    (ssl-simple/gen-cert
     certname
     ca-map
     1000
     ;; Set this artificially lower than default 4K that Puppet Server uses
     ;; just to make it a bit faster.  Key size is presumed not to matter
     ;; for the core functionality in any tests that use this.
     {:keylength 1024})))

(schema/defn get-ssl-context-for-cert-map :- SSLContext
  [ca-cert :- ca/Certificate
   cert-map :- (schema/pred ssl-simple/ssl-cert?)]
  (ssl-utils/generate-ssl-context
   {:ssl-cert (write-to-stream (:cert cert-map))
    :ssl-key (write-to-stream (:private-key cert-map))
    :ssl-ca-cert (write-to-stream ca-cert)}))
