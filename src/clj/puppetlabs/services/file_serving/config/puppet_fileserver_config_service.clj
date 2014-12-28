(ns ^{:doc
      "Implementation of the PuppetFileserverConfigService."}
    puppetlabs.services.file-serving.config.puppet-fileserver-config-service

  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.services.protocols.puppet-fileserver-config :refer [PuppetFileserverConfigService]]
            [puppetlabs.services.file-serving.config.puppet-fileserver-config-core :as core]
            [puppetlabs.trapperkeeper.services :as tk-services]))

(tk/defservice puppet-fileserver-config-service
               PuppetFileserverConfigService
               [[:PuppetServerConfigService get-in-config get-config]
                ]

               (init
                 [this context]
                 (let [puppet-fileserver-config (get-in-config [:puppet-server :fileserverconfig])
                       mounts (core/fileserver-parse puppet-fileserver-config)]
                   (log/debugf "Loaded file-serving mounts from %s" puppet-fileserver-config)
                   (assoc context :fileserver-mounts mounts)))

               (find-mount
                 [this path]
                 (let [context (tk-services/service-context this)
                       mounts (:fileserver-mounts context)]
                   (core/find-mount mounts path)))

               (allowed?
                 [this request mount]
                 (core/allowed? request mount)))
