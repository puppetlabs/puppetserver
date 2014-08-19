(ns puppetlabs.puppetserver.liberator-utils
  (:require [clojure.tools.logging :as log]))

(defn exception-handler
  "A general-purpose exception handler for a liberator resource.
  By default, liberator swallows exceptions.  This function simply logs the
  exception and the HTTP request that caused it."
  [context]
  (let [msg (str "Error handling request: " (:request context))]
    (log/error (:exception context) msg))

  ; Liberator uses the return value of this function as the repsonse body.
  "Internal server error.")
