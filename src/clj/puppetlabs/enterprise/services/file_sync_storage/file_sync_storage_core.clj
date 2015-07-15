(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
  (:import (org.eclipse.jgit.api Git InitCommand)
           (org.eclipse.jgit.api.errors GitAPIException JGitInternalException)
           (clojure.lang Keyword)
           (com.puppetlabs.enterprise NoGitlinksWorkingTreeIterator))
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.ringutils :as ringutils]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+ throw+]]
            [compojure.core :as compojure]
            [ring.middleware.json :as ring-json]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [puppetlabs.trapperkeeper.services.status.status-core :as status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Defaults

(def default-commit-author-name "PE File Sync Service")

(def default-commit-author-email "")

(def default-commit-message "Publish content to file sync storage service")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

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
    {:working-dir common/StringOrFile
     :submodules-dir common/StringOrFile
     :submodules-working-dir common/StringOrFile}
    {:working-dir common/StringOrFile}))

(def GitRepos
  {schema/Keyword GitRepo})

(def FileSyncServiceRawConfig
  "Schema defining the full content of the JGit service configuration.

  The keys should have the following values:

    * :repos     - A sequence with metadata about each of the individual
                   Git repositories that the server manages.
    * :preserve-submodule-repos - A boolean indicating whether or not the bare
                                  repos for submodules should be preserved
                                  on-disk when that submodule is deleted from
                                  the relevant repo. Defaults to false."
  {:repos GitRepos
   (schema/optional-key :preserve-submodule-repos) schema/Bool})

(def PublishRequestBase
  {(schema/optional-key :message) schema/Str
   (schema/optional-key :author) {:name  schema/Str
                                  :email schema/Str}})
(def PublishRequest
  (schema/maybe
    (assoc PublishRequestBase
      (schema/optional-key :repo-id) schema/Str)))

(def PublishRequestWithSubmodule
  (assoc PublishRequestBase
    :repo-id schema/Str
    :submodule-id schema/Str))

(def PublishRequestBody
  "Schema defining the body of a request to the publish content endpoint.

  The body is optional, but if supplied it must be a map with the
  following optional values:

    * :message - Commit message

    * :author  - Map containing :name and :email of author for commit

    * :repo-id - The id of a specific repo to publish. When provided, only
                 the specified repo will be published, rather than all repos.

    * :submodule-id - The id of a specific submodule to publish within the
                      repo specified by :repo-id. When provided, only the
                      specified submodule will be published, rather than all
                      submodules within the specified repo. If :submodule-id
                      is present, :repo-id must be present as well."
  (schema/if :submodule-id
    PublishRequestWithSubmodule
    PublishRequest))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn path-to-data-dir
  [data-dir]
  (str data-dir "/storage"))

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

(schema/defn latest-commit-id-on-master :- (schema/maybe common/LatestCommit)
  "Returns the SHA-1 revision ID of the latest commit on the master branch of
   the repository specified by the given `git-dir`.  Returns `nil` if no commits
   have been made on the repository."
  [git-dir working-dir]
  (when-let [repo (jgit-utils/get-repository-from-git-dir git-dir)]
    (when-let [ref (.getRef repo "refs/heads/master")]
      (let [latest-commit (-> ref
                              (.getObjectId)
                              (jgit-utils/commit-id))
            submodules-status (jgit-utils/get-submodules-latest-commits
                                git-dir working-dir)
            base-data {:commit latest-commit}]
        (if (empty? submodules-status)
          base-data
          (assoc base-data :submodules submodules-status))))))

(defn compute-latest-commits
  "Computes the latest commit for each repository in sub-paths.  Returns a map
   of sub-path -> commit ID."
  [data-dir repos]
  (let [sub-paths (keys repos)
        map* (if (> (count sub-paths) 1) pmap map)
        latest-commit (fn [sub-path]
                        (let [repo-path (common/bare-repo data-dir sub-path)
                              rev (latest-commit-id-on-master
                                    repo-path
                                    (get-in repos [sub-path :working-dir]))]
                          [sub-path rev]))]
    (into {}
      (map* latest-commit sub-paths))))

(schema/defn commit-author :- common/Identity
  "Returns an Identity given the author information from a publish request,
  using defaults for name and e-mail address if not specified."
  [author]
  {:name (:name author default-commit-author-name)
   :email (:email author default-commit-author-email)})

(defn add-all-and-rm-missing
  "Add all additions and modifications to the index, including those in
  subdirectories with nested .git directories. Then remove from the index
  anything removed from the working tree, including files removed from
  subdirectories with nested .git directories, but not from anything in any
  submodules in the submodules-dir."
  ([git]
   (add-all-and-rm-missing git nil))
  ([git submodules-dir]
  ;; Add everything that has been modified or added to the git index
  (-> git
    .add
    (.addFilepattern ".")
    (.setWorkingTreeIterator
      (NoGitlinksWorkingTreeIterator. (.getRepository git)))
    .call)
   ;; Get all the things in the index with a status 'missing' (i.e. missing in
   ;; the working tree, present in the git index), using the
   ;; NoGitlinksWorkingTreeIterator (thus anything removed from a directory
   ;; with a nested .git directory will show up as 'missing').
   (let [status (-> git
                  .status
                  (.setWorkingTreeIt
                    (NoGitlinksWorkingTreeIterator. (.getRepository git)))
                  .call)
         missing (.getMissing status)
         ;; Filter out from the 'missing' list anything in the submodules-dir,
         ;; since this will all show up as 'missing' since it is a gitlink.
         ;; If submodules-dir is nil, then this will remove nothing (since
         ;; no paths should start with "/"; they are all relative).
         removed (remove
                   #(re-matches (re-pattern (str submodules-dir "/.*")) %)
                   missing)]
    ;; Remove everything that should be removed from the git index.
    (when-not (empty? removed)
      (let [rm-command (.rm git)]
        (reduce #(.addFilepattern %1 %2) rm-command removed)
        (.call rm-command))))))

(defn add-all-with-submodules
  "Add/remove everything from the parent repo, without gitlinks, then add all
  the submodules as gitlinks."
  [git submodules-dir]
  ;; Add/remove everything as appropriate from the parent repo using the
  ;; NoGitlinksWorkingTreeIterator.
  (add-all-and-rm-missing git submodules-dir)
  ;; Remove anything that has been cached so far that is in the
  ;; submodules-dir, since previously we were using the
  ;; 'NoGitlinksWorkingTreeIterator', which does not work with submodules.
  (when submodules-dir
    (-> git
      .reset
      (.addPath submodules-dir)
      .call)
    ;; Add the submodules-dir to the index, with the default
    ;; 'FileTreeIterator' (which will use gitlinks).
    (-> git
      .add
      (.addFilepattern submodules-dir)
      .call)))

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
    (add-all-and-rm-missing submodule-git)
    (jgit-utils/commit
      submodule-git (:message commit-info) (:identity commit-info)))

  ;; do a submodule add on the parent repo and return the SHA from the cloned
  ;; submodule within the repo
  (log/debugf "Adding submodule to parent repo at %s" submodule-path)
  (let [repo  (jgit-utils/submodule-add! parent-git submodule-path submodule-url)]
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
    (add-all-and-rm-missing submodule-git)
    (jgit-utils/commit
      submodule-git (:message commit-info) (:identity commit-info)))

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
  (doall
    (for [submodule submodules]
      (let [repo-name (name repo)
            submodule-git-dir (common/submodule-bare-repo data-dir repo-name submodule)
            submodule-working-dir (fs/file submodules-working-dir submodule)
            submodule-path (submodule-path submodules-dir submodule)
            submodule-within-parent (fs/file working-dir submodule-path)
            submodule-url (format "%s/%s/%s.git" server-repo-url repo-name submodule)
            parent-git (-> (common/bare-repo data-dir repo-name)
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
            (failed-to-publish submodule-within-parent e)))))))

(defn remove-submodules!
  "Given a repository and the list of submodules in the submodules-working-dir,
   remove any submodules that no longer exist in the submodules-working-dir
   from the repository"
  [repo submodules submodules-dir commit-identity data-dir repo-id preserve-submodules?]
  (let [submodules-in-repo (set (jgit-utils/get-submodules repo))
        submodules (set (map #(str submodules-dir "/" %) submodules))
        deleted-submodules (set/difference submodules-in-repo submodules)]
    (doseq [submodule deleted-submodules]
      (jgit-utils/remove-submodule! repo submodule)
      (when-not preserve-submodules?
        (let [submodules-repo-dir (fs/file data-dir (name repo-id))]
          (fs/delete-dir (fs/file submodules-repo-dir
                           (str (jgit-utils/extract-submodule-name submodule) ".git"))))))
    (when-not (empty? deleted-submodules)
      (let [commit-message (str "Delete submodules: "
                             (apply str (interpose ", " deleted-submodules)))
            git (Git. repo)]
        (jgit-utils/add! git ".gitmodules")
        (jgit-utils/commit git commit-message commit-identity)))))

(schema/defn publish-repos :- [PublishRepoResult]
  "Given a list of working directories, a commit message, and a commit author,
  perform an add and commit for each working directory.  Returns the newest
  SHA for each working directory that was successfully committed and the
  status of any submodules in the repo, or an error if the add/commit failed."
  [repos :- GitRepos
   data-dir :- schema/Str
   server-repo-url :- schema/Str
   preserve-submodules? :- schema/Bool
   commit-info :- common/CommitInfo
   submodule-id :- (schema/maybe schema/Str)]
  (for [[repo-id {:keys [working-dir submodules-dir submodules-working-dir]} :as repo] repos]
    (do
      (log/infof "Publishing working directory %s to file sync storage service"
        working-dir)
      (let [all-submodules (when submodules-working-dir (fs/list-dir (fs/file submodules-working-dir)))
            submodules (if submodule-id
                         (filter #(= % submodule-id) all-submodules)
                         all-submodules)
            git-dir (common/bare-repo data-dir repo-id)
            git-repo (jgit-utils/get-repository git-dir working-dir)]
        (try
          (log/infof "Removing deleted submodules for repo %s" working-dir)
          (remove-submodules!
            git-repo all-submodules submodules-dir (:identity commit-info)
            data-dir repo-id preserve-submodules?)
          (let [submodules-status (publish-submodules
                                    submodules repo data-dir
                                    server-repo-url commit-info)
                git (Git/wrap git-repo)
                commit (do (log/infof "Committing repo %s" working-dir)
                           (add-all-with-submodules git submodules-dir)
                           (jgit-utils/commit git (:message commit-info) (:identity commit-info)))
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
   server-repo-url
   preserve-submodules?]
  (if-let [checked-body (schema/check PublishRequestBody body)]
    (throw+ {:type    :user-data-invalid
             :message (str "Request body did not match schema: "
                           checked-body)})
    (let [repo-id (keyword (:repo-id body))
          repos-to-publish (if repo-id
                             (select-keys repos [repo-id])
                             repos)
          submodule-id (:submodule-id body)
          commit-info {:identity (commit-author (:author body))
                       :message (:message body default-commit-message)}
          new-commits (publish-repos
                        repos-to-publish
                        data-dir
                        server-repo-url
                        preserve-submodules?
                        commit-info
                        submodule-id)]
      (zipmap (keys repos-to-publish) new-commits))))

(schema/defn repos-status
  [repos :- GitRepos
   data-dir]
  (into {}
    (for [[repo-id {:keys [working-dir]}] repos]
      (let [repo (jgit-utils/get-repository
                   (common/bare-repo data-dir repo-id)
                   working-dir)]
        {repo-id {:latest-commit (jgit-utils/commit->status-info (jgit-utils/latest-commit repo))
                  :working-dir (jgit-utils/repo-status-info repo)
                  :submodules (jgit-utils/submodules-status-info repo)}}))))

(defn capture-publish-info!
  [!request-tracker request result]
  (swap! !request-tracker assoc
    :latest-publish {:client-ip-address (:remote-addr request)
                     :timestamp (common/timestamp)
                     :repos result}))

(defn capture-client-info!
  [!request-tracker request client-repos-info]
  (let [ip-address (:remote-addr request)]
    (swap! !request-tracker assoc-in
      [:clients ip-address] (assoc client-repos-info
                              :last-check-in-time (common/timestamp)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Ring handler

(defn base-ring-handler
  "Returns a Ring handler for the File Sync Storage Service's API using the
   given configuration values."
  [data-dir repos server-repo-url preserve-submodules? !request-tracker]
  (compojure/routes
    (compojure/context "/v1" []
      (compojure/POST common/publish-content-sub-path {:keys [body headers] :as request}
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
              (let [json-body (json/parse-string (slurp body) true)
                    result (publish-content
                             repos json-body data-dir server-repo-url preserve-submodules?)]
                (capture-publish-info! !request-tracker request result)
                {:status 200
                 :body result})
              (catch com.fasterxml.jackson.core.JsonParseException e
                {:status 400
                 :body {:error {:type :json-parse-error
                                :message "Could not parse body as JSON."}}}))
            {:status 400
             :body {:error {:type :content-type-error
                            :message (format
                                       "Content type must be JSON, '%s' is invalid"
                                       content-type)}}})))
      (compojure/POST common/latest-commits-sub-path {:keys [body] :as request}
        (let [json-body (json/parse-string (slurp body) true)]
          (capture-client-info! !request-tracker request json-body))
        {:status 200
         :body (compute-latest-commits data-dir repos)}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn ring-handler
  "Returns a Ring handler (created via base-ring-handler) and wraps it in all of
   necessary middleware for error handling, logging, etc."
  [data-dir sub-paths server-repo-url preserve-submodules? !request-tracker]
  (-> (base-ring-handler data-dir sub-paths server-repo-url preserve-submodules? !request-tracker)
    ringutils/wrap-request-logging
    ringutils/wrap-user-data-errors
    ringutils/wrap-schema-errors
    ringutils/wrap-errors
    ring-json/wrap-json-response
    ringutils/wrap-response-logging))

(schema/defn ^:always-validate initialize-repos!
  "Initialize the repositories managed by this service.  For each repository ...
    * There is a directory under data-dir (specified in config) which is actual Git
      repository (git dir).
    * If working-dir does not exist, it will be created.
    * If there is not an existing Git repo under data-dir,
      'git init' will be used to create one."
  [config :- FileSyncServiceRawConfig
   data-dir :- schema/Str]
  (log/infof "Initializing file sync server data dir: %s" data-dir)
  (initialize-data-dir! (fs/file data-dir))
  (doseq [[repo-id repo-info] (:repos config)]
    (let [working-dir (:working-dir repo-info)
          git-dir (common/bare-repo data-dir repo-id)]
      ; Create the working dir, if it does not already exist.
      (when-not (fs/exists? working-dir)
        (ks/mkdirs! working-dir))
      (log/infof "Initializing Git repository at %s" git-dir)
      (initialize-bare-repo! git-dir))))

(schema/defn ^:always-validate status :- status/StatusCallbackResponse
  [level :- Keyword
   repos :- GitRepos
   data-dir :- common/StringOrFile
   request-data]
  {:state :running
   :status (when (not= level :critical)
             (assoc request-data
               :timestamp (common/timestamp)
               :repos (repos-status repos data-dir)))})
