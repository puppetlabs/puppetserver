(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service
  (:import (org.eclipse.jgit.http.server GitServlet))
  (:require
    [clojure.tools.logging :as log]
    [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core :as core]
    [puppetlabs.trapperkeeper.core :refer [defservice]]
    [compojure.core :as compojure]))

(defservice file-sync-storage-service
            [[:ConfigService get-in-config]
             [:WebroutingService add-servlet-handler add-ring-handler get-route]]

  (init [this context]
    (let [config            (get-in-config [:file-sync-storage])
          base-path         (:base-path config)
          api-path-prefix   (get-route this :api)
          repo-path-prefix  (get-route this :repo-servlet)
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
        this
        (GitServlet.)
        {:servlet-init-params {"base-path" base-path "export-all" export-all}
         :route-id            :repo-servlet})

      (let [repos (:repos config)
            handler (core/build-handler base-path repos)]

        (log/info "Registering file sync storage HTTP API at" api-path-prefix)

        (add-ring-handler
          this
          (compojure/context api-path-prefix [] handler)
          {:route-id :api})))

    context))
