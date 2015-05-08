(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tks]
            [puppetlabs.ssl-utils.core :as ssl]
            [schema.core :as schema]))

(defprotocol FileSyncClientService
  (register-callback [this repo-id callback-fn]))

(tk/defservice file-sync-client-service
  FileSyncClientService
  [[:ConfigService get-in-config]
   [:ShutdownService request-shutdown]
   [:SchedulerService after stop-job]]

  (init [this context]
    (log/info "Initializing file sync client service")
    (assoc context :callbacks (atom {})))

  (start [this context]
    (log/info "Starting file sync client service")
    (let [config (get-in-config [:file-sync-client])
          poll-interval (* (:poll-interval config) 1000)
          ssl-context (ssl/generate-ssl-context config)
          sync-agent (core/create-agent request-shutdown)]
      (core/configure-jgit-client-ssl! ssl-context)

      (let [schedule-fn (partial after poll-interval)
            http-client (core/create-http-client ssl-context)
            callbacks   (deref (:callbacks context))]
        (core/start-periodic-sync-process!
          sync-agent schedule-fn config http-client callbacks)
        (assoc context :agent sync-agent
                       :http-client http-client))))

  (register-callback [this repo-id callback-fn]
    (let [context (tks/service-context this)]
      (core/register-callback! context repo-id callback-fn)))

  (stop [this context]
    (log/info "Stopping file sync client service")

    (log/trace "closing HTTP client")
    (.close (:http-client context))

    context))
