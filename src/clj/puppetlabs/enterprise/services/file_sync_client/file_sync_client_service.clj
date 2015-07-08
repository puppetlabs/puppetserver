(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tks]
            [puppetlabs.ssl-utils.core :as ssl]
            [puppetlabs.enterprise.services.protocols.file-sync-client :refer :all]
            [puppetlabs.enterprise.file-sync-common :as common]))

(tk/defservice file-sync-client-service
  FileSyncClientService
  [[:ConfigService get-in-config]
   [:ShutdownService request-shutdown]
   [:SchedulerService after stop-job]
   [:StatusService register-status]]

  (init [this context]
    (log/info "Initializing file sync client service")
    (register-status
      "file-sync-client-service"
      common/artifact-version
      1
      #(core/status %))
    (assoc context :callbacks (atom {})))

  (start [this context]
    (log/info "Starting file sync client service")
    (let [client-config (get-in-config [:file-sync-client])
          common-config (get-in-config [:file-sync-common])
          server-url (:server-url common-config)
          data-dir (core/path-to-data-dir (:data-dir common-config))
          poll-interval (* (:poll-interval client-config) 1000)
          ssl-context (ssl/generate-ssl-context client-config)
          sync-agent (core/create-agent request-shutdown)]
      (core/configure-jgit-client-ssl! ssl-context)

      (let [schedule-fn (partial after poll-interval)
            http-client (core/create-http-client ssl-context)
            callbacks (deref (:callbacks context))
            config {:client-config client-config
                    :server-url server-url
                    :data-dir data-dir}]
        (core/start-periodic-sync-process!
          sync-agent schedule-fn config http-client callbacks)
        (assoc context :agent sync-agent
                       :http-client http-client
                       :config client-config
                       :common-config common-config
                       :started? true))))

  (register-callback! [this repo-ids callback-fn]
    (let [context (tks/service-context this)]
      (when (:started? context)
        (throw (IllegalStateException.
                 "Callbacks must be registered before the File Sync Client is started")))
      (core/register-callback! context repo-ids callback-fn)))

  (sync-working-dir! [this repo-id working-dir]
    (core/sync-working-dir! (core/path-to-data-dir (get-in-config [:file-sync-common :data-dir]))
                            repo-id
                            working-dir))

  (stop [this context]
    (log/info "Stopping file sync client service")

    (log/trace "closing HTTP client")

    ;; This is needed, as if the app fails during startup, the
    ;; http-client will be nil
    (when-let [client (:http-client context)]
      (.close client))

    context))
