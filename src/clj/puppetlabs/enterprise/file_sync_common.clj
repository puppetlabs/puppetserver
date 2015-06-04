(ns puppetlabs.enterprise.file-sync-common
  (:import (javax.net.ssl SSLContext))
  (:require [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Url paths

(def latest-commits-sub-path "/latest-commits")
(def publish-content-sub-path "/publish")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas


(def FileSyncCommonConfig
  "Schema defining the content of the configuration common to the File Sync
  client and storage services.

  The keys should have the following values:

    * :server-url - Base URL of the repository server."
  {:server-url schema/Str})

(def LatestCommitsPayload
  "Schema defining the return payload of the server's 'latest-commits'
  endpoint.

  The first Str in each pair represents a repository name.  The corresponding
  Str in the pair represents the id of the latest commit in the repository."
  {schema/Str (schema/maybe schema/Str)})

(def SSLContextOrNil
  (schema/maybe SSLContext))

(def LatestCommitsResponse
  {:body    Object
   :status  (schema/eq 200)
   :headers {(schema/required-key "content-type")
             (schema/pred #(.startsWith % "application/json"))
             schema/Any schema/Any}
   schema/Any schema/Any})
