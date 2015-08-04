(ns puppetlabs.enterprise.file-sync-common
  (:import (javax.net.ssl SSLContext)
           (java.io File)
           (org.eclipse.jgit.lib PersonIdent Repository))
  (:require [cheshire.core :as json]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.services.status.status-core :as status]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]))

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

(def LatestCommitOrError
  "Schema defining the result of computing the latest commit for a repo"
  (schema/if #(:error %)
    {:error schema/Str}
    {:commit (schema/maybe schema/Str)
     (schema/optional-key :submodules) {schema/Str schema/Str}}))

(def LatestCommitsPayload
  "Schema defining the return payload of the server's 'latest-commits'
  endpoint.

  The first Str in each pair represents a repository name.  The corresponding
  Str in the pair represents the id of the latest commit in the repository."
  {schema/Keyword (schema/maybe LatestCommitOrError)})

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

(defn bare-repo-path
  "Given a path to a data-dir (either client or storage service) and a repo-name,
  returns the path on disk to the bare Git repository with the given name."
  [data-dir repo-name]
  (fs/file data-dir (str (name repo-name) ".git")))

(defn submodule-bare-repo-path
  [data-dir parent-repo submodule]
  (fs/file data-dir (name parent-repo) (str submodule ".git")))

(schema/defn identity->person-ident :- PersonIdent
  [{:keys [name email]} :- Identity]
  (PersonIdent. name email))

(defn extract-submodule-name
  [submodule]
  (re-find #"[^\/]+$" submodule))

(schema/defn repo->latest-commit-status-info
  "Given a Repository, extracts and returns information about its latest commit
  which is relevant for the /status endpoint.  Returns nil if the repository
  has no commits."
  [repo :- Repository]
  (when-let [commit (jgit-utils/latest-commit repo)]
    {:commit (jgit-utils/commit-id commit)
     :date (jgit-time->human-readable (.getCommitTime commit))
     :message (.getFullMessage commit)
     :author {:name (.getName (.getAuthorIdent commit))
              :email (.getEmailAddress (.getAuthorIdent commit))}}))

(defn working-dir-status-info
  [repo]
  (let [repo-status (jgit-utils/status repo)]
    {:clean (.isClean repo-status)
     :modified (.getModified repo-status)
     :missing (.getMissing repo-status)
     :untracked (.getUntracked repo-status)}))

(defn submodules-status-info
  [repo]
  (->> repo
    jgit-utils/submodules-status
    (ks/mapvals
      (fn [ss]
        {:path (.getPath ss)
         :status (.toString (.getType ss))
         :head_id (jgit-utils/commit-id (.getHeadId ss))}))))
