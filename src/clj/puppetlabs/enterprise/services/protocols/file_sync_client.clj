(ns puppetlabs.enterprise.services.protocols.file-sync-client)

(defprotocol FileSyncClientService

  (register-callback! [this repo-ids callback-fn]
    "Registers a callback function for the repos with the provided set of
    repo-ids, which will be called when the client's clones of one or more
    of the registered repos are synced. The callback should accept one argument,
    which will be a map containing the statuses for all repos for which the
    callback is registered.")

  (sync-working-dir! [this repo-id working-dir]
    "Syncs the contents of the repo with repo-id managed by the client
    service into a working directory specified by working-dir."))
