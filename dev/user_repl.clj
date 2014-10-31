(ns user-repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.services.master.master-service :refer [master-service]]
            [puppetlabs.services.request-handler.request-handler-service :refer [request-handler-service]]
            [puppetlabs.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.services.jruby.testutils :as jruby-testutils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :refer [puppet-profiler-service]]
            [puppetlabs.services.config.puppet-server-config-service :refer [puppet-server-config-service]]
            [puppetlabs.services.ca.certificate-authority-service :refer [certificate-authority-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]
            [clojure.java.io :as io]))

(defn jvm-puppet-conf
  "This function returns a map containing all of the config settings that
  will be used when running Puppet Server in the repl.  It provides some
  reasonable defaults, but if you'd like to use your own settings, you can
  define a var `jvm-puppet-conf` in your `user` namespace, and those settings
  will be used instead.  (If there is a `user.clj` on the classpath, lein
  will automatically load it when the REPL is started.)"
  []
  (if-let [conf (resolve 'user/jvm-puppet-conf)]
    (deref conf)
    {:global                {:logging-config "./dev/logback-dev.xml"}
     :os-settings           {:ruby-load-path jruby-testutils/ruby-load-path}
     :jruby-puppet          {:gem-home             jruby-testutils/gem-home
                             :max-active-instances 1
                             :master-conf-dir      jruby-testutils/conf-dir}
     :webserver             {:client-auth "want"
                             :ssl-host    "localhost"
                             :ssl-port    8140}
     :certificate-authority {:certificate-status {:client-whitelist []}}}))

(def system nil)

(defn init []
  (alter-var-root #'system
    (fn [_] (tk/build-app
              [jetty9-service
               master-service
               jruby-puppet-pooled-service
               puppet-profiler-service
               request-handler-service
               puppet-server-config-service
               certificate-authority-service]
              (jvm-puppet-conf))))
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
  (refresh :after 'user-repl/go))