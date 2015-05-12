(ns puppetlabs.enterprise.services.protocols.file-sync-client)

(defprotocol FileSyncClientService

  (register-callback! [this repo-id callback-fn]
    "Registers a callback function for a repo with the given repo-id, which
    will be called when the client's clone of that repo is updated")

  (sync-working-dir! [this repo-id working-dir]
    "Syncs the contents of the repo with repo-id managed by the client
    service into a working directory specified by working-dir."))
