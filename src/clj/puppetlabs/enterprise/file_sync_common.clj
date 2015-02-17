(ns puppetlabs.enterprise.file-sync-common
  (:import (javax.net.ssl SSLContext))
  (:require [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Url paths

(def latest-commits-sub-path "/latest-commits")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def LatestCommitsPayload
  "Schema defining the return payload of the server's 'latest-commits'
  endpoint.

  The first Str in each pair represents a repository name.  The corresponding
  Str in the pair represents the id of the latest commit in the repository."
  {schema/Str (schema/maybe schema/Str)})

(def SSLContextOrNil
  (schema/maybe SSLContext))
