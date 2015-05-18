(ns user-repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service :refer [file-sync-storage-service]]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service :refer [file-sync-client-service]]
            [puppetlabs.enterprise.services.scheduler.scheduler-service :refer [scheduler-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [puppetlabs.trapperkeeper.config :as tkc]
            [clojure.tools.namespace.repl :refer (refresh)]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]))

(defn file-sync-storage-conf
  "This function returns a map of config settings that will be used
   when running the file sync storage service in the REPL. It provides some
   reasonable defaults but if you'd like to use your own settings you can define
   a var `file-sync-storage-conf` in your `user` namespace and those settings
   will be used instead. (If there is a `user.clj` on the classpath, lein will
   automatically load it when the REPL is started"
  []
  (let [conf-path (when-let [conf (resolve 'user/file-sync-storage-conf-path)]
                    (deref conf))
        user-conf (when conf-path
                    (try
                      (tkc/load-config conf-path)
                      (catch Exception e
                        (log/error (str "User-specified storage config path "
                                        "cannot be loaded. Using the contents "
                                        "of dev.conf instead."))
                        nil)))]
    (or user-conf (tkc/load-config "./dev/dev.conf"))))

(defn file-sync-client-conf
  "This function returns a map of config settings that will be used
   when running the file sync client service in the REPL. It provides some
   reasonable defaults but if you'd like to use your own settings you can define
   a var `file-sync-client-conf` in your `user` namespace and those settings
   will be used instead. (If there is a `user.clj` on the classpath, lein will
   automatically load it when the REPL is started"
  []
  (let [conf-path (when-let [conf (resolve 'user/file-sync-client-conf-path)]
                    (deref conf))
        user-conf (when conf-path
                    (try
                      (tkc/load-config conf-path)
                      (catch Exception e
                        (log/error (str "User-specified client config path "
                                        "cannot be loaded. Using the contents "
                                        "of dev.conf instead."))
                        nil)))]
    (or user-conf (tkc/load-config "./dev/dev.conf"))))

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
