(ns puppetlabs.services.versioned-code-service.versioned-code-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.protocols.versioned-code :as vc]
            [puppetlabs.services.versioned-code-service.versioned-code-core :as vc-core]
            [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(trapperkeeper/defservice versioned-code-service
                          vc/VersionedCodeService
                          [[:ConfigService get-in-config]]

  (init
   [this context]
   (if (nil? (get-in-config [:versioned-code :code-id-command]))
     (log/info "No code-id-command set for versioned-code-service. Code-id will be nil."))
   context)

  (current-code-id
   [this environment]
   (if-let [code-id-script (get-in-config [:versioned-code :code-id-command])]
     (vc-core/execute-code-id-script! code-id-script environment)
     nil)))