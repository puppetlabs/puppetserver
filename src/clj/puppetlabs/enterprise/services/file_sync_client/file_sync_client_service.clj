(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.ssl-utils.core :as ssl]))

; This is unfortunate, but needed for testing.  In tests, we call
; '(tk-app/get-service app :FileSyncClientService)', and that does not work
; unless this service implements a protocol.
(defprotocol FileSyncClientService)

(tk/defservice file-sync-client-service
  FileSyncClientService
  [[:ConfigService get-in-config]
   [:ShutdownService request-shutdown]
   [:SchedulerService after stop-job]]

  (start [this context]
    (log/info "Starting file sync client service")
    (let [config (get-in-config [:file-sync-client])
          poll-interval (* (:poll-interval config) 1000)
          ssl-context (ssl/generate-ssl-context config)
          sync-agent (core/create-agent request-shutdown)]
      (core/configure-jgit-client-ssl! ssl-context)

      (let [schedule-fn (partial after poll-interval)
            http-client (core/create-http-client ssl-context)]
        (core/start-periodic-sync-process!
          sync-agent schedule-fn config http-client)
        (assoc context :agent sync-agent
                       :http-client http-client))))

  (stop [this context]
    (log/info "Stopping file sync client service")

    (log/trace "closing HTTP client")
    (.close (:http-client context))

    context))
