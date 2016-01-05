(ns puppetlabs.services.protocols.versioned-code)

(defprotocol VersionedCodeService
  "A TK service for interacting with versioned puppet code."

  (current-code-id
   [this environment]
   "Returns the current code id (representing the freshest code) for the given environment."))
