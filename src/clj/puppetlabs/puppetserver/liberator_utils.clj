(ns puppetlabs.puppetserver.liberator-utils)

(defn exception-handler
  "Handles exceptions which occur inside a liberator resource by simply
  re-throwing them so they can be handled elsewhere, like middleware."
  [context]
  (throw (:exception context)))
