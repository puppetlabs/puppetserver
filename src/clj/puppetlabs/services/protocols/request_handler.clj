(ns puppetlabs.services.protocols.request-handler)

(defprotocol RequestHandlerService
  (handle-request
    [this request]
    "Handles a request from an agent."))
