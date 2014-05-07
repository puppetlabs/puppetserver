(ns puppetlabs.master.services.protocols.request-handler)

(defprotocol RequestHandlerService
  (handle-request
    [this request]
    [this environment request]
    "Handles a request from an agent."))
