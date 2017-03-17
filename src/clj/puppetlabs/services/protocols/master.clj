(ns puppetlabs.services.protocols.master)

(defprotocol MasterService
  "Protocol for the master service."

  (add-metric-ids-to-http-client-metrics-list!
    [this metric-ids-to-add]
   "Append a list of metric-ids to the built in list (defined in
   `master-core/puppet-server-http-client-metrics-for-status`) that gets included in the
   `http-client-metrics` key in the status endpoint."))
