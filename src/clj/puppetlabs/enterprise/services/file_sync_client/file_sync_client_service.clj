(ns puppetlabs.enterprise.services.file-sync-client.file-sync-client-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as core]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.ssl-utils.core :as ssl]))

(tk/defservice file-sync-client-service
               [[:ConfigService get-in-config]
                [:ShutdownService shutdown-on-error]
                [:SchedulerService interspaced stop-job]]

  (start [this context]
    (log/info "Starting file sync client service")
    (let [config (get-in-config [:file-sync-client])
          poll-interval (* (:poll-interval config) 1000)
          ssl-context (ssl/generate-ssl-context config)]
      (core/configure-jgit-client-ssl! ssl-context)

      (let [http-client (core/create-http-client ssl-context)
            sync-job #(core/perform-sync! config http-client)
            ; schedule the sync job
            job-id (interspaced poll-interval sync-job)]
        (assoc context :http-client http-client
                       :job-id job-id))))

  (stop [this context]
    (log/info "Stopping file sync client service")

    (log/trace "Stopping scheduled job")
    (stop-job (:job-id context))

    (log/trace "closing HTTP client")
    (.close (:http-client context))

    context))
