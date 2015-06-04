(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
  (:import (java.io File)
           (org.eclipse.jgit.api Git InitCommand)
           (org.eclipse.jgit.lib PersonIdent)
           (org.eclipse.jgit.api.errors GitAPIException JGitInternalException))
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.ringutils :as ringutils]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+ throw+]]
            [compojure.core :as compojure]
            [ring.middleware.json :as ring-json]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Defaults

(def default-commit-author-name "PE File Sync Service")

(def default-commit-author-email "")

(def default-commit-message "Publish content to file sync storage service")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def StringOrFile (schema/pred
                    (fn [x] (or (instance? String x) (instance? File x)))
                    "String or File"))

(def GitRepo
  "Schema defining a Git repository.

  The keys should have the following values:

    * :working-dir  - The path where the repository's working tree resides.

    * :submodules-dir - The relative path within the repository's working tree
                        where submodules will be added.

    * :submodules-working-dir - The path in which to look for directories to
                                be added as submodules to the repository.

  `submodules-dir` and `submodules-working-dir` are optional, but if one is
  present the other must be too."
  (schema/if :submodules-dir
    {:working-dir StringOrFile
     :submodules-dir StringOrFile
     :submodules-working-dir StringOrFile}
    {:working-dir StringOrFile}))

(def GitRepos
  {schema/Keyword GitRepo})

(def FileSyncServiceRawConfig
  "Schema defining the full content of the JGit service configuration.

  The keys should have the following values:

    * :data-dir - The data directory on the Git server under which all of the
                  repositories it is managing should reside.

    * :repos     - A sequence with metadata about each of the individual
                   Git repositories that the server manages."
  {:data-dir StringOrFile
   :repos    GitRepos})

(def PublishRequestBody
  "Schema defining the body of a request to the publish content endpoint.

  The body is optional, but if supplied it must be a map with the
  following optional values:

    * :message - Commit message

    * :author  - Map containing :name and :email of author for commit "
  (schema/maybe
    {(schema/optional-key :message) schema/Str
     (schema/optional-key :author) {:name schema/Str
                                    :email schema/Str}
     (schema/optional-key :repo-id) schema/Str}))

(def PublishError
  "Schema defining an error when attempting to publish a repo."
  {:error {:type    (schema/eq :publish-error)
           :message schema/Str}})

(def PublishSubmoduleResult
  "Schema defining the result of publishing a single submodule, which is
  either a SHA for the new commit or an error map."
  {schema/Str (schema/if map? PublishError schema/Str)})

(def PublishSuccess
  "Schema defining the result of successfully adding and commiting a single
  repo, including the status of publishing any submodules the repo has."
  {:commit schema/Str
   (schema/optional-key :submodules) PublishSubmoduleResult})

(def PublishRepoResult
  "Schema defining the result of publishing a single repo, which is either a
  map with the SHA for the new commit and the status of any submodules, if
  there are any on the storage server, or an error map."
  (schema/if :commit
    PublishSuccess
    PublishError))

(def PublishResponseBody
  "Schema defining the body of a response to the publish content endpoint.

  The response is a map of repo name to repo status, which is either a
  SHA or an error map."
  {schema/Keyword PublishRepoResult})

(def CommitInfo
  "Schema defining the necessary metadata for making a commit."
  {:author PersonIdent
   :message schema/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn initialize-data-dir!
  "Initialize the data directory under which all git repositories will be hosted."
  [data-dir]
  (try+
    (ks/mkdirs! data-dir)
    (catch map? m
      (throw
        (Exception. (str "Unable to create file sync data-dir:" (:message m)))))
    (catch Exception e
      (throw (Exception. "Unable to create file sync data-dir." e)))))

(defn initialize-bare-repo!
  "Initialize a bare Git repository in the directory specified by 'path'."
  [path]
  (.. (new InitCommand)
      (setDirectory (io/as-file path))
      (setBare true)
      (call)))

(defn latest-commit-on-master
  "Returns the SHA-1 revision ID of the latest commit on the master branch of
   the repository specified by the given `git-dir`.  Returns `nil` if commits
   have been made on the repository."
  [git-dir]
  {:pre [(instance? File git-dir)]
   :post [(or (string? %) (nil? %))]}
  (when-let [repo (jgit-utils/get-repository-from-git-dir git-dir)]
    (when-let [ref (.getRef repo "refs/heads/master")]
      (-> ref
          (.getObjectId)
          (jgit-utils/commit-id)))))

(defn compute-latest-commits
  "Computes the latest commit for each repository in `sub-paths`."
  [data-dir sub-paths]
  (reduce
    (fn [acc sub-path]
      (let [repo-path (fs/file data-dir (str (name sub-path) ".git"))
            rev (latest-commit-on-master repo-path)]
        (assoc acc sub-path rev)))
    {}
    sub-paths))

(defn commit-author
  "Create PersonIdent instance using provided name and email, or
  defaults if not provided."
  [author]
  (let [name (:name author default-commit-author-name)
        email (:email author default-commit-author-email)]
   (PersonIdent. name email)))

(defn failed-to-publish
  [path error]
  (log/error error (str "Failed to publish " path))
  {:error {:type :publish-error
           :message (str "Failed to publish " path ": "
                      (.getMessage error))}})

(defn init-new-submodule
  "Initialize a new submodule by creating a git dir for it, adding and
  commiting it, and adding it as a submodule to its parent repo. Returns the
  submodule's new SHA."
  [submodule-git-dir
   submodule-working-dir
   submodule-path
   parent-git
   submodule-url
   commit-info]

  ;; initialize a bare repo for the new submodule
  (log/debugf "Initializing bare repo for submodule at %s" submodule-git-dir)
  (initialize-bare-repo! submodule-git-dir)

  ;; add and commit the new submodule
  (log/debugf "Committing submodule %s" submodule-working-dir)
  (let [submodule-git (Git/wrap (jgit-utils/get-repository submodule-git-dir submodule-working-dir))]
    (jgit-utils/add-and-commit submodule-git (:message commit-info) (:author commit-info)))

  ;; do a submodule add on the parent repo and return the SHA from the cloned
  ;; submodule within the repo
  (log/debugf "Adding submodule to parent repo at %s" submodule-path)
  (let [repo  (.. parent-git
                submoduleAdd
                (setPath submodule-path)
                (setURI submodule-url)
                call)]
    (jgit-utils/head-rev-id repo)))

(defn publish-existing-submodule
  "Publishes an existing submodule by adding and commiting its git repo, and
  then doing a git pull for it within the parent repo. Returns the submodule's
  new SHA."
  [submodule-git-dir
   submodule-working-dir
   submodule-within-parent
   commit-info]

  ;; add and commit the repo for the submodule
  (log/debugf "Committing submodule %s " submodule-working-dir)
  (let [submodule-git (Git/wrap (jgit-utils/get-repository submodule-git-dir submodule-working-dir))]
    (jgit-utils/add-and-commit submodule-git (:message commit-info) (:author commit-info)))

  ;; do a pull for the submodule within the parent repo to update it, and
  ;; the return the SHA for the new HEAD of the submodule.
  (log/debugf "Updating submodule %s within parent repo" submodule-within-parent)
  (-> submodule-within-parent
    jgit-utils/get-repository-from-working-tree
    jgit-utils/pull
    .getMergeResult
    .getNewHead
    jgit-utils/commit-id))

(defn submodule-path
  "Return the path to the submodule within the parent repo. If
  'submodules-dir' is an empty string, then the submodule will be located at
  the root of the repo, otherwise it will be underneath 'submodules-dir'."
  [submodules-dir submodule]
  (if (empty? submodules-dir)
    submodule
    (str submodules-dir "/" submodule)))

(defn publish-submodules
  "Given a list of subdirectories, checks to see whether each subdirectory is
  already a submodule of the parent repo. If so, does an add and commit on the
  subdirectory and a git pull on its directory within the parent repo to
  update it. If not, initializes a bare repo for it, does an initial add and
  commit, then adds it as a submodule of the parent repo. Returns a list with
  either an error or the new SHA for each submodule."
  [submodules
   [repo {:keys [submodules-dir submodules-working-dir working-dir]}]
   data-dir
   server-repo-url
   commit-info]
  (for [submodule submodules]
    (let [repo-name (name repo)
          submodule-git-dir (fs/file data-dir repo-name (str submodule ".git"))
          submodule-working-dir (fs/file submodules-working-dir submodule)
          submodule-path (submodule-path submodules-dir submodule)
          submodule-within-parent (fs/file working-dir submodule-path)
          submodule-url (format "%s/%s/%s.git" server-repo-url repo-name submodule ".git")
          parent-git (-> (fs/file data-dir (str repo-name ".git"))
                       (jgit-utils/get-repository working-dir)
                       Git/wrap)]

      (log/infof "Publishing submodule %s for repo %s" submodule-path repo-name)
      ;; Check whether the submodule exists on the parent repo. If it does
      ;; then we add and commit the submodule in its repo, then do a pull
      ;; within the parent repo to update it there. If it does not exist, then
      ;; we need to initialize a new bare repo for the submodule and do a
      ;; "submodule add".
      (try
        (if (empty? (.. parent-git
                      submoduleStatus
                      (addPath submodule-path)
                      call))
          (init-new-submodule
            submodule-git-dir
            submodule-working-dir
            submodule-path
            parent-git
            submodule-url
            commit-info)
          (publish-existing-submodule
            submodule-git-dir
            submodule-working-dir
            submodule-within-parent
            commit-info))
        (catch JGitInternalException e
          (failed-to-publish submodule-within-parent e))
        (catch GitAPIException e
          (failed-to-publish submodule-within-parent e))))))

(schema/defn publish-repos :- [PublishRepoResult]
  "Given a list of working directories, a commit message, and a commit author,
  perform an add and commit for each working directory.  Returns the newest
  SHA for each working directory that was successfully committed and the
  status of any submodules in the repo, or an error if the add/commit failed."
  [repos :- GitRepos
   data-dir :- schema/Str
   server-repo-url :- schema/Str
   commit-info :- CommitInfo]
  (for [[repo-id {:keys [working-dir submodules-dir submodules-working-dir]} :as repo] repos]
    (do
      (log/infof "Publishing working directory %s to file sync storage service"
        working-dir)
      (let [submodules (when submodules-working-dir (fs/list-dir (fs/file submodules-working-dir)))
            submodules-status (doall (publish-submodules submodules repo data-dir server-repo-url commit-info))]
        (try
          (log/infof "Committing repo %s" working-dir)
          (let [git-dir (fs/file data-dir (str (name repo-id) ".git"))
                git (Git/wrap (jgit-utils/get-repository git-dir working-dir))
                commit (jgit-utils/add-and-commit git (:message commit-info) (:author commit-info))
                parent-status {:commit (jgit-utils/commit-id commit)}]
            (if-not (empty? submodules-status)
              (assoc parent-status :submodules
                (zipmap (map #(submodule-path submodules-dir %) submodules) submodules-status))
              parent-status))
          (catch JGitInternalException e
            (failed-to-publish working-dir e))
          (catch GitAPIException e
            (failed-to-publish working-dir e)))))))

(schema/defn ^:always-validate publish-content :- PublishResponseBody
  "Given a map of repositories and the JSON body of the request, publish
  each working directory to the file sync storage service, using the
  contents of the body - if provided - for the commit author and
  message. Returns a map of repository name to status - either SHA of
  latest commit or error."
  [repos :- GitRepos
   body
   data-dir
   server-repo-url]
  (if-let [checked-body (schema/check PublishRequestBody body)]
    (throw+ {:type    :user-data-invalid
             :message (str "Request body did not match schema: "
                           checked-body)})
    (let [repo-id (keyword (:repo-id body))
          repos (if repo-id
                  (select-keys repos [repo-id])
                  repos)
          commit-info {:author (commit-author (:author body))
                       :message (:message body default-commit-message)}
          new-commits (publish-repos repos data-dir server-repo-url commit-info)]
      (zipmap (keys repos) new-commits))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app
(defn build-routes
  "Builds the compojure routes from the given configuration values."
  [data-dir repos server-repo-url]
  (compojure/routes
    (compojure/context "/v1" []
      (compojure/POST common/publish-content-sub-path {body :body headers :headers}
        ;; The body can either be empty - in which a
        ;; "Content-Type" header should not be required - or
        ;; it can be JSON. If it is empty, JSON parsing will
        ;; still work (since it is the empty string), so we
        ;; can just try to parse this as JSON and return an
        ;; error if that fails.
        (let [content-type (headers "content-type")]
          (if (or (nil? content-type)
                (re-matches #"application/json.*" content-type))
            (try
              (let [json-body (json/parse-string (slurp body) true)]
                {:status 200
                 :body (publish-content repos json-body data-dir server-repo-url)})
              (catch com.fasterxml.jackson.core.JsonParseException e
                {:status 400
                 :body {:error {:type :json-parse-error
                                :message "Could not parse body as JSON."}}}))
            {:status 400
             :body {:error {:type :content-type-error
                            :message "Content type must be JSON."}}})))
      (compojure/ANY common/latest-commits-sub-path []
        {:status 200
         :body (compute-latest-commits data-dir (keys repos))}))))

(defn build-handler
  "Builds a ring handler from the given configuration values."
  [data-dir sub-paths server-repo-url]
  (-> (build-routes data-dir sub-paths server-repo-url)
      ringutils/wrap-request-logging
      ringutils/wrap-user-data-errors
      ringutils/wrap-schema-errors
      ringutils/wrap-errors
      ring-json/wrap-json-response
      ringutils/wrap-response-logging))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate initialize-repos!
  "Initialize the repositories managed by this service.  For each repository ...
    * There is a directory under data-dir (specified in config) which is actual Git
      repository (git dir).
    * If working-dir does not exist, it will be created.
    * If there is not an existing Git repo under data-dir,
      'git init' will be used to create one."
  [config :- FileSyncServiceRawConfig]
  (let [data-dir (:data-dir config)]
    (log/infof "Initializing file sync server data dir: %s" data-dir)
    (initialize-data-dir! (fs/file data-dir))
    (doseq [[repo-id repo-info] (:repos config)]
      (let [working-dir (:working-dir repo-info)
            git-dir (fs/file data-dir (str (name repo-id) ".git"))]
        ; Create the working dir, if it does not already exist.
        (when-not (fs/exists? working-dir)
          (ks/mkdirs! working-dir))
        (log/infof "Initializing Git repository at %s" git-dir )
        (initialize-bare-repo! git-dir)))))
