(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
  (:import (org.eclipse.jgit.transport HttpTransport))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core
              :as core]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.enterprise.file-sync-common :as common]))

(tk/defservice file-sync-client-service
               [[:ConfigService get-in-config]
                [:ShutdownService shutdown-on-error]]

  (start [this context]
    (log/info "Starting file sync client service")
    (let [config               (get-in-config
                                 [:file-sync-client])
          shutdown-requested?  (promise)
          ssl-opts             (common/extract-ssl-opts config)]
      ; Ensure the JGit client is configured for SSL if necessary
      (HttpTransport/setConnectionFactory (jgit-client/create-connection-factory ssl-opts))
      (future
        (shutdown-on-error
          (tk-services/service-id this)
          #(core/start-worker config shutdown-requested? ssl-opts)))
      (assoc context :file-sync-client-shutdown-requested?
                     shutdown-requested?)))

  (stop [this context]
    (log/info "Stopping file sync client service")
    (if-let [shutdown-requested?
             (:file-sync-client-shutdown-requested? context)]
      (core/stop-worker shutdown-requested?))
    context))