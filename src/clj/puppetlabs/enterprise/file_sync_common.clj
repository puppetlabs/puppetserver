(ns puppetlabs.enterprise.file-sync-common
  (:import (javax.net.ssl SSLContext)
           (java.io File)
           (org.eclipse.jgit.lib PersonIdent))
  (:require [cheshire.core :as json]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.status.status-core :as status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Url paths

(def latest-commits-sub-path "/latest-commits")
(def publish-content-sub-path "/publish")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def artifact-version (status/get-artifact-version "puppetlabs" "pe-file-sync"))

(def datetime-formatter
  "The date/time formatter used to produce timestamps using clj-time.
   This matches the format used by PuppetDB."
  (time-format/formatters :date-time))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def StringOrFile (schema/pred
                    (fn [x] (or (instance? String x) (instance? File x)))
                    "String or File"))

; TODO - this is unused - see PE-10688
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

(defn format-date-time
  "Given a DateTime object, return a human-readable, formatted string."
  [date-time]
  (time-format/unparse datetime-formatter date-time))

(defn timestamp
  "Returns a nicely-formatted string of the current date/time."
  []
  (format-date-time (time/now)))

(defn jgit-time->human-readable
  "Given a commit time from JGit, format it into a human-readable date/time string."
  [commit-time]
  (format-date-time
    (time/plus
      (time/epoch)
      (time/seconds commit-time))))

(defn parse-latest-commits-response
  [response]
  (->> response
       :body
       json/parse-string
      (ks/mapkeys keyword)
      (ks/mapvals (fn [v]
                    (when v
                      (ks/mapkeys keyword v))))))

(defn bare-repo
  [data-dir repo-name]
  (fs/file data-dir (str (name repo-name) ".git")))

(defn submodule-bare-repo
  [data-dir parent-repo submodule]
  (fs/file data-dir (name parent-repo) (str submodule ".git")))

(schema/defn identity->person-ident :- PersonIdent
  [{:keys [name email]} :- Identity]
  (PersonIdent. name email))
