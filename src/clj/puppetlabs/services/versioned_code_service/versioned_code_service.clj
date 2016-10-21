(ns puppetlabs.services.versioned-code-service.versioned-code-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.protocols.versioned-code :as vc]
            [puppetlabs.services.versioned-code-service.versioned-code-core :as vc-core]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(trapperkeeper/defservice versioned-code-service
                          vc/VersionedCodeService
                          [[:ConfigService get-in-config]]

  (init
   [this context]
   (if (nil? (get-in-config [:versioned-code :code-id-command]))
     (log/info (i18n/trs "No code-id-command set for versioned-code-service. Code-id will be nil.")))
   (if (nil? (get-in-config [:versioned-code :code-content-command]))
     (log/info (i18n/trs "No code-content-command set for versioned-code-service. Attempting to fetch code content will fail.")))
   (vc-core/validate-config! (get-in-config [:versioned-code]))
   context)

  (current-code-id
   [this environment]
   (if-let [code-id-script (get-in-config [:versioned-code :code-id-command])]
     (vc-core/get-current-code-id! code-id-script environment)
     nil))

  (get-code-content
   [this environment code-id file-path]
   (if-let [code-content-script (get-in-config [:versioned-code :code-content-command])]
     (vc-core/execute-code-content-script!
       code-content-script environment code-id file-path)
     (throw (IllegalStateException. (i18n/trs "Cannot retrieve code content because the \"versioned-code.code-content-command\" setting is not present in configuration."))))))
