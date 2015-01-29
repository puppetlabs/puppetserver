(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service
  (:import (org.eclipse.jgit.http.server GitServlet))
  (:require
    [clojure.tools.logging :as log]
    [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as core]
    [puppetlabs.enterprise.file-sync-common :as common]
    [puppetlabs.trapperkeeper.core :refer [defservice]]
    [compojure.core :as compojure]))

(defservice file-sync-storage-service
            [[:ConfigService get-in-config]
             [:WebserverService add-servlet-handler add-ring-handler]]

  (init [this context]
    (let [config            (get-in-config [:file-sync-storage])
          base-path         (:base-path config)
          api-path-prefix   (or (:api-path-prefix config)
                                common/default-api-path-prefix)
          repo-path-prefix  (or (:repo-path-prefix config)
                                common/default-repo-path-prefix)
          ; JGit servlet uses 'export-all' setting to decide
          ; whether to allow all repositories to be eligible
          ; for external access.  Using a value of '1' for now
          ; -- everything exported -- until a need to do
          ; something different for security arises.
          export-all "1"]
      (core/initialize-repos! config)

      (log/info
         "File sync storage service mounting repositories at" repo-path-prefix)

      (add-servlet-handler
        (GitServlet.)
        repo-path-prefix
        {:servlet-init-params {"base-path" base-path "export-all" export-all}})

      (let [sub-paths (map :sub-path (:repos config))
            handler   (core/build-handler base-path sub-paths)]

        (log/info "Registering file sync storage HTTP API at" api-path-prefix)

        (add-ring-handler
          (compojure/context api-path-prefix [] handler)
          api-path-prefix)))

    context))
