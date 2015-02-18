(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-utils
  (:require [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
             :as file-sync-client-service]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service
             :as jetty-service]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.app :as tk-app]))

(defmacro with-boostrapped-file-sync-client-and-webserver
  [webserver-config ring-handler client-config & body]
  `(bootstrap/with-app-with-config
     webserver-app#
     [jetty-service/jetty9-service]
     ~webserver-config
     (let [target-webserver# (tk-app/get-service webserver-app# :WebserverService)]
       (jetty-service/add-ring-handler
         target-webserver#
         ~ring-handler
         "/"))
     (bootstrap/with-app-with-config
       client-app#
       [file-sync-client-service/file-sync-client-service]
       {:file-sync-client ~client-config}
       (do
         ~@body))))
