(ns user-repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.services.master.master-service :refer [master-service]]
            [puppetlabs.services.request-handler.request-handler-service :refer [request-handler-service]]
            [puppetlabs.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :refer [puppet-profiler-service]]
            [puppetlabs.services.config.puppet-server-config-service :refer [puppet-server-config-service]]
            [puppetlabs.services.ca.certificate-authority-service :refer [certificate-authority-service]]
            [puppetlabs.services.puppet-admin.puppet-admin-service :refer [puppet-admin-service]]
            [puppetlabs.services.legacy-routes.legacy-routes-service :refer [legacy-routes-service]]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]
            [clojure.pprint :as pprint]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Configuration

(defn puppet-server-conf
  "This function returns a map containing all of the config settings that
  will be used when running Puppet Server in the repl.  It provides some
  reasonable defaults, but if you'd like to use your own settings, you can
  define a var `puppet-server-conf` in your `user` namespace, and those settings
  will be used instead.  (If there is a `user.clj` on the classpath, lein
  will automatically load it when the REPL is started.)"
  []
  (if-let [conf (resolve 'user/puppet-server-conf)]
    ((deref conf))
    {:global                {:logging-config "./dev/logback-dev.xml"}
     :jruby-puppet          (jruby-testutils/jruby-puppet-config {:max-active-instances 1
                                                                  :use-legacy-auth-conf false})
     :webserver             {:client-auth "want"
                             :ssl-host    "localhost"
                             :ssl-port    8140}
     :web-router-service    {:puppetlabs.services.ca.certificate-authority-service/certificate-authority-service "/puppet-ca"
                             :puppetlabs.services.master.master-service/master-service                           "/puppet"
                             :puppetlabs.services.puppet-admin.puppet-admin-service/puppet-admin-service         "/puppet-admin-api"
                             :puppetlabs.services.legacy-routes.legacy-routes-service/legacy-routes-service      ""}
     :authorization         {:rules [{:path  "/"
                                      :type  "path"
                                      :allow "*"}]}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Basic system life cycle

(def system nil)

(defn init []
  (alter-var-root #'system
    (fn [_] (tk/build-app
              [jetty9-service
               webrouting-service
               master-service
               jruby-puppet-pooled-service
               puppet-profiler-service
               request-handler-service
               puppet-server-config-service
               certificate-authority-service
               puppet-admin-service
               legacy-routes-service
               authorization-service]
              (puppet-server-conf))))
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

(defn reset []
  (stop)
  (refresh :after 'user-repl/go))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities for interacting with running system

(defn context
  "Get the current TK application context.  Accepts an optional array
  argument, which is treated as a sequence of keys to retrieve a nested
  subset of the map (a la `get-in`)."
  ([]
   (context []))
  ([keys]
   (get-in @(tka/app-context system) keys)))

(defn print-context
  "Pretty-print the current TK application context.  Accepts an optional
  array of keys (a la `get-in`) to print a nested subset of the context."
  ([]
   (print-context []))
  ([keys]
   (pprint/pprint (context keys))))

(defn jruby-pool
  "Returns a reference to the current pool of JRuby interpreters."
  []
  (jruby-core/registered-instances (context [:JRubyPuppetService :pool-context])))

(defn puppet-environment-state
  "Given a JRuby instance, return the state information about the environments
  that it is aware of."
  [jruby-instance]
  {:jruby-instance-id (:id jruby-instance)
   :environment-states (-> jruby-instance
                             :environment-registry
                             puppet-env/environment-state
                             deref)})

(defn print-puppet-environment-states
  "Print state information about the environments that each JRuby instance is
  aware of."
  []
  (pprint/pprint
    (map puppet-environment-state (jruby-pool))))

(defn mark-environment-expired!
  "Mark the specified environment, on all JRuby instances, stale so that it will
  be flushed from the environment cache."
  [env-name]
  (jruby-protocol/mark-environment-expired!
    (tka/get-service system :JRubyPuppetService)
    env-name))

(defn mark-all-environments-expired!
  "Mark all environments, on all JRuby instances, stale so that they will
  be flushed from the environment cache."
  []
  (jruby-protocol/mark-all-environments-expired!
    (tka/get-service system :JRubyPuppetService)))

(defn flush-jruby-pool!
  "Flush and repopulate the JRuby pool"
  []
  (jruby-protocol/flush-jruby-pool!
    (tka/get-service system :JRubyPuppetService)))
