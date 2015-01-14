(ns puppetlabs.services.protocols.puppet-fileserver-config)

(defprotocol PuppetFileserverConfigService
  "The file server configuration service. This service allows to find
  a given mount for a Puppet file serving request, and also check if the
  request is allowed."

  (find-mount [this path]
              "Returns the mount name and acl for a given request path, along with
              the proper real path or an empty vector if the mount doesn't exist")

  (allowed? [this request mount]
                 "Returns true if the given mount allows the given request otherwise false."))
