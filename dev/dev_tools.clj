(ns dev-tools
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.services.master.master-service :refer [master-service]]
            [puppetlabs.services.request-handler.request-handler-service :refer [request-handler-service]]
            [puppetlabs.services.jruby.jruby-puppet-service :refer [jruby-puppet-pooled-service]]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :refer [puppet-profiler-service]]
            [puppetlabs.services.config.puppet-server-config-service :refer [puppet-server-config-service]]
            [puppetlabs.services.ca.certificate-authority-service :refer [certificate-authority-service]]
            [puppetlabs.services.puppet-admin.puppet-admin-service :refer [puppet-admin-service]]
            [puppetlabs.services.legacy-routes.legacy-routes-service :refer [legacy-routes-service]]
            [puppetlabs.trapperkeeper.services.authorization.authorization-service :refer [authorization-service]]
            [puppetlabs.services.versioned-code-service.versioned-code-service :refer [versioned-code-service]]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :refer [jruby-pool-manager-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.services.status.status-service :refer [status-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]
            [clojure.pprint :as pprint]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.config :as config]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Configuration

(defn get-default-config
  []
  (config/load-config "./dev/puppetserver.conf"))

(defn get-config
  "This function is required by the Puppet clojure repo best practices guide.  It
  will be called during the bootstrapping of the application via `go`, and will
  be passed as an argument a copy of the default configuration map for the dev
  environment for the app; users may override the implementation function in their
  `user.clj` to make customizations to the configuration map."
  [default-config]
  default-config)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Basic system life cycle

(def ^:private system nil)

(defn- init []
  (alter-var-root #'system
    (fn [_] (tk/build-app
             (bootstrap/parse-bootstrap-config! "./dev/bootstrap.cfg")
             (get-config (get-default-config)))))
  (alter-var-root #'system tka/init)
  (tka/check-for-errors! system))

(defn- start []
  (alter-var-root #'system
                  (fn [s] (if s (tka/start s))))
  (tka/check-for-errors! system))

(defn stop
  "Stop the running server"
  []
  (alter-var-root #'system
                  (fn [s] (when s (tka/stop s)))))

(defn go
  "Initialize and start the server"
  []
  (init)
  (start))

(defn reset
  "Stop the running server, reload code, and restart."
  []
  (stop)
  (refresh :after 'dev-tools/go))

(defn help
  "Prints a list of all of the public functions and their docstrings"
  []
  (let [fns (ns-publics 'dev-tools)]
    (doseq [f fns]
      ;; TODO; inspect arglists
      (println (format "(%s): %s\n"
                       (key f)
                       (-> (val f) meta :doc))))))

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
  (jruby-core/registered-instances (context [:service-contexts :JRubyPuppetService :pool-context])))

(defn- puppet-environment-state
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
