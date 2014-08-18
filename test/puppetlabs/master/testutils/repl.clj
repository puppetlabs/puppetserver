(ns puppetlabs.master.testutils.repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.master.services.master.master-service :refer [master-service]]
            [puppetlabs.master.services.handler.request-handler-service :refer [request-handler-service]]
            [puppetlabs.master.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.master.services.puppet-profiler.puppet-profiler-service :refer [puppet-profiler-service]]
            [puppetlabs.master.services.config.puppet-server-config-service :refer [puppet-server-config-service]]
            [puppetlabs.master.services.ca.certificate-authority-service :refer [certificate-authority-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]))

(def system nil)

(defn init []
  (alter-var-root #'system
    (fn [_] (let [conf-dir "./scratch/master/conf"]
              (tk/build-app
                [jetty9-service
                 master-service
                 jruby-puppet-pooled-service
                 puppet-profiler-service
                 request-handler-service
                 puppet-server-config-service
                 certificate-authority-service]
                {:global {:logging-config "./dev-resources/logback-dev.xml"}
                 :jruby-puppet { :jruby-pools  [{:environment "production"
                                                 :size 1}]
                                 :load-path    ["./ruby/puppet/lib"
                                                "./ruby/facter/lib"]
                                 :master-conf-dir conf-dir}
                 :webserver {:client-auth "want"
                             :ssl-host    "localhost"
                             :ssl-port    8140}}))))
  (alter-var-root #'system tka/init)
  (tka/check-for-errors! system))

(defn start []
  (alter-var-root #'system
                  (fn [s] (if s (tka/start s))))
  (tka/check-for-errors! system))

(defn stop []
  (alter-var-root #'system
                  (fn [s] (when s (tka/stop s)))))

(defn go []
  (init)
  (start))

(defn context []
  @(tka/app-context system))

(defn print-context []
  (clojure.pprint/pprint (context)))

(defn reset []
  (stop)
  (refresh :after 'puppetlabs.master.testutils.repl/go))