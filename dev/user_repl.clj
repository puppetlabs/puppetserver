(ns user-repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service :refer [file-sync-storage-service]]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service :refer [file-sync-client-service]]
            [puppetlabs.enterprise.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]
            [clojure.pprint :as pprint]))

(defn file-sync-storage-conf
  "This function returns a map of config settings that will be used
   when running the file sync services in the repl. It provides some reasonable
   defaults but if you'd like to use your own settings you can define a var
   `file-sync-fong` in your `user` namespace and those settings will be used instead.
   (If there is a `user.clj` on the classpath, lein will automatically load it
   the REPL is started"
  []
  (if-let [conf (resolve 'user/file-sync-storage-conf)]
    ((deref conf))
    {:global             {:logging-config "./dev/logback.xml"}
     :webserver          {:port 8080}
     :web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                          {:api          "/file-sync"
                           :repo-servlet "/git"}}
     :file-sync-storage  {:base-path ".file-sync/.file-sync-server"
                          :repos     {:repl-repo {:working-dir       "repl-repo-working"
                                                  :http-push-enabled true}}}}))

(defn file-sync-client-conf
  "This function returns a map of config settings that will be used
   when running the file sync services in the repl. It provides some reasonable
   defaults but if you'd like to use your own settings you can define a var
   `file-sync-fong` in your `user` namespace and those settings will be used instead.
   (If there is a `user.clj` on the classpath, lein will automatically load it
   the REPL is started"
  []
  (if-let [conf (resolve 'user/file-sync-client-conf)]
    ((deref conf))
    {:global             {:logging-config "./dev/logback.xml"}
     :file-sync-client   {:server-url       "http://localhost:8080"
                          :poll-interval    1
                          :server-api-path  "/file-sync"
                          :server-repo-path "/git"
                          :repos            {:repl-repo ".file-sync/.file-sync-client/repl-repo"}}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Basic system life cycle

(def system-storage nil)
(def system-client nil)

(defn init-storage []
  (alter-var-root #'system-storage
                  (fn [_] (tk/build-app
                            [jetty9-service
                             webrouting-service
                             file-sync-storage-service]
                            (file-sync-storage-conf))))
  (alter-var-root #'system-storage tka/init)
  (tka/check-for-errors! system-storage))

(defn init-client []
  (alter-var-root #'system-client
                  (fn [_] (tk/build-app
                            [file-sync-client-service
                             scheduler-service]
                            (file-sync-client-conf))))
  (alter-var-root #'system-client tka/init)
  (tka/check-for-errors! system-client))

(defn init []
  (init-storage)
  (init-client))

(defn start-storage []
  (alter-var-root #'system-storage
                  (fn [s] (if s (tka/start s))))
  (tka/check-for-errors! system-storage))

(defn start-client []
  (alter-var-root #'system-client
                  (fn [s] (if s (tka/start s))))
  (tka/check-for-errors! system-client))

(defn start []
  (start-storage)
  (start-client))

(defn stop-storage []
  (alter-var-root #'system-storage
                  (fn [s] (when s (tka/stop s)))))

(defn stop-client []
  (alter-var-root #'system-client
                  (fn [s] (when s (tka/stop s)))))

(defn stop []
  (stop-client)
  (stop-storage))

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
  ([system]
   (context system []))
  ([system keys]
   (get-in @(tka/app-context system) keys)))

(defn context-client
  ([]
   (context system-client))
  ([keys]
   (context system-client keys)))

(defn context-storage
  ([]
   (context system-storage))
  ([keys]
   (context system-storage keys)))

(defn print-context-client
  ([]
   (print-context-client []))
  ([keys]
   (pprint/pprint (context-client keys))))

(defn print-context-storage
  ([]
   (print-context-storage []))
  ([keys]
   (pprint/pprint (context-storage keys))))
