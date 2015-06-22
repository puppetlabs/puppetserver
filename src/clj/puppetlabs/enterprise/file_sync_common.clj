(ns puppetlabs.enterprise.file-sync-common
  (:import (javax.net.ssl SSLContext))
  (:require [cheshire.core :as json]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.status.status-core :as status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Url paths

(def latest-commits-sub-path "/latest-commits")
(def publish-content-sub-path "/publish")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def artifact-version (status/get-artifact-version "puppetlabs" "pe-file-sync"))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas


(def FileSyncCommonConfig
  "Schema defining the content of the configuration common to the File Sync
  client and storage services.

  The keys should have the following values:

    * :data-dir - The data directory under which all of the bare repositories
                  being managed (both on the client and server side) should
                  reside.

    * :server-url - Base URL of the repository server."
  {:data-dir schema/Str
   :server-url schema/Str})

(def LatestCommit
  "Schema defining the result of computing the latest commit for a repo"
  {:commit schema/Str
   (schema/optional-key :submodules) {schema/Str schema/Str}})

(def LatestCommitsPayload
  "Schema defining the return payload of the server's 'latest-commits'
  endpoint.

  The first Str in each pair represents a repository name.  The corresponding
  Str in the pair represents the id of the latest commit in the repository."
  {schema/Keyword (schema/maybe LatestCommit)})

(def SSLContextOrNil
  (schema/maybe SSLContext))

(def LatestCommitsResponse
  {:body    Object
   :status  (schema/eq 200)
   :headers {(schema/required-key "content-type")
             (schema/pred #(.startsWith % "application/json"))
             schema/Any schema/Any}
   schema/Any schema/Any})

(def Identity
  "Schema for an author/committer."
  {:name String
   :email String})

(def CommitInfo
  "Schema defining the necessary metadata for making a commit."
  {:identity Identity
   :message schema/Str})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Functions

(defn parse-latest-commits-response
  [response]
  (->> response
       :body
       json/parse-string
      (ks/mapkeys keyword)
      (ks/mapvals (fn [v]
                    (when v
                      (ks/mapkeys keyword v))))))
