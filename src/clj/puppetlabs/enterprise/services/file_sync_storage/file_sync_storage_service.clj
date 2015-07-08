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
   [:WebroutingService add-servlet-handler add-ring-handler get-route]
   [:StatusService register-status]]

  (init [this context]
    (let [config (get-in-config [:file-sync-storage])
          data-dir (core/path-to-data-dir (get-in-config [:file-sync-common :data-dir]))
          server-url (get-in-config [:file-sync-common :server-url])
          api-path-prefix (get-route this :api)
          repo-path-prefix (get-route this :repo-servlet)
          server-repo-url (str server-url repo-path-prefix)
          ; JGit servlet uses 'export-all' setting to decide
          ; whether to allow all repositories to be eligible
          ; for external access.  Using a value of '1' for now
          ; -- everything exported -- until a need to do
          ; something different for security arises.
          export-all "1"]
      (core/initialize-repos! config data-dir)
      (log/info
        "File sync storage service mounting repositories at" repo-path-prefix)
      (add-servlet-handler
        this
        (GitServlet.)
        {:servlet-init-params {"base-path" data-dir "export-all" export-all}
         :route-id :repo-servlet})
      (let [repos (:repos config)
            !request-tracker (atom nil)
            preserve-submodules? (:preserve-submodule-repos config false)
            handler (core/ring-handler data-dir repos server-repo-url preserve-submodules? !request-tracker)]
        (log/info "Registering file sync storage HTTP API at" api-path-prefix)
        (add-ring-handler
          this
          (compojure/context api-path-prefix [] handler)
          {:route-id :api})
        ; Register status function with the TK Status Service
        (register-status
          "file-sync-storage-service"
          common/artifact-version
          1
          #(core/status % (:repos config) data-dir @!request-tracker))))
    context))
