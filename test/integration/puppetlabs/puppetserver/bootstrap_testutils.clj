(ns puppetlabs.puppetserver.bootstrap-testutils
  (:require [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.kitchensink.core :as ks]
            [me.raynes.fs :as fs]))

(def dev-config-file
  "./dev/puppetserver.conf.sample")

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

(defn load-dev-config-with-overrides
  [overrides]
  (let [tmp-conf (ks/temp-file "puppetserver" ".conf")]
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
  (let [config (load-dev-config-with-overrides config-overrides)]
    `(let [services# (tk-bootstrap/parse-bootstrap-config! ~dev-bootstrap-file)]
       (tk-testutils/with-app-with-config
         ~app
         services#
         ~config
         ~@body))))
