(ns puppetlabs.services.file-serving.file-metadata.file-metadata-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.file-serving.file-metadata.file-metadata-core :as core]
            [puppetlabs.trapperkeeper.services :as service]
            [puppetlabs.services.protocols.file-metadata :refer :all]
            [compojure.core :as compojure]))

(tk/defservice file-metadata-service
               FileMetadataService
               [PuppetFileserverConfigService
                [:WebroutingService add-ring-handler get-route]
                ]
               (init
                 [this context]
                 (log/info "About to start FileMetadata Service")
                 (let [path (get-route this)]
                   (log/info "FileMetadata Service adding a ring handler")
                   (add-ring-handler
                     this
                     (compojure/context path [] (core/build-ring-handler (service/get-service this :PuppetFileserverConfigService)))))
                 context)

               )

