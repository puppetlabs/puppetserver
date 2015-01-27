(ns puppetlabs.services.protocols.request-handler)

(defprotocol RequestHandlerService
  (handle-request
    [this request]
    "Handles a request from an agent.")
  (handle-invalid-request
    [this request]
    "Handles an incorrectly formattted Puppet 4 request from an agent."))
