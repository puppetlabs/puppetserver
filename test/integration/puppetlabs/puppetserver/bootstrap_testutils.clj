(ns puppetlabs.puppetserver.bootstrap-testutils
  (:require [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]))

(def dev-config-file
  "./dev/puppet-server.conf.sample")

(def dev-bootstrap-file
  "./dev/bootstrap.cfg")

(def logging-test-conf-file
  "./dev-resources/logback-test.xml")

(def master-conf-dir
  "./target/master-conf")

(def master-code-dir
  "./target/master-code")

(def master-var-dir
  "./target/master-var")

(def master-run-dir
  "./target/master-var/run")

(def master-log-dir
  "./target/master-var/log")

(defn pem-file
  [& args]
  (str (apply fs/file master-conf-dir "ssl" args)))

(defn load-dev-config-with-overrides
  [overrides]
  (let [tmp-conf (ks/temp-file "puppet-server" ".conf")]
    (fs/copy dev-config-file tmp-conf)
    (-> (tk-config/load-config (.getPath tmp-conf))
        (assoc-in [:global :logging-config] logging-test-conf-file)
        (assoc-in [:jruby-puppet :master-conf-dir] master-conf-dir)
        (assoc-in [:jruby-puppet :master-code-dir] master-code-dir)
        (assoc-in [:jruby-puppet :master-var-dir] master-var-dir)
        (assoc-in [:jruby-puppet :master-run-dir] master-run-dir)
        (assoc-in [:jruby-puppet :master-log-dir] master-log-dir)
        (ks/deep-merge overrides))))

(defmacro with-puppetserver-running-with-services
  [app services config-overrides & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(tk-testutils/with-app-with-config
       ~app
       ~services
       ~config
       ~@body)))

(defmacro with-puppetserver-running
  [app config-overrides & body]
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(let [services# (tk-bootstrap/parse-bootstrap-config! ~dev-bootstrap-file)]
       (tk-testutils/with-app-with-config
         ~app
         services#
         ~config
         ~@body))))

(defn pem-file
  [& args]
  (str (apply fs/file master-conf-dir "ssl" args)))

(def ca-cert
  (pem-file "certs" "ca.pem"))

(def localhost-cert
  (pem-file "certs" "localhost.pem"))

(def localhost-key
  (pem-file "private_keys" "localhost.pem"))

(def request-options
  {:ssl-cert    localhost-cert
   :ssl-key     localhost-key
   :ssl-ca-cert ca-cert
   :headers     {"Accept" "pson"}
   :as          :text})
