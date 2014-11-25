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

(def master-var-dir
  "./target/master-var")

(defn pem-file
  [& args]
  (str (apply fs/file master-conf-dir "ssl" args)))

(defmacro with-puppetserver-running
  [app config-overrides & body]
  (let [tmp-conf (ks/temp-file "puppet-server" ".conf")]
    (fs/copy dev-config-file tmp-conf)
    (let [config (-> (tk-config/load-config (.getPath tmp-conf))
                     (assoc-in [:global :logging-config] logging-test-conf-file)
                     (assoc-in [:jruby-puppet :master-conf-dir] master-conf-dir)
                     (assoc-in [:jruby-puppet :master-var-dir] master-var-dir)
                     (ks/deep-merge config-overrides))]
      `(let [services# (tk-bootstrap/parse-bootstrap-config! ~dev-bootstrap-file)]
         (tk-testutils/with-app-with-config
           ~app
           services#
           ~config
           ~@body)))))
