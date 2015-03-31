(ns puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-core
  (:import (java.io File)
           (org.eclipse.jgit.api Git InitCommand)
           (org.eclipse.jgit.lib PersonIdent)
           (org.eclipse.jgit.api.errors GitAPIException)
           (org.eclipse.jgit.errors RepositoryNotFoundException))
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.jgit-client :as client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.enterprise.ringutils :as ringutils]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+]]
            [compojure.core :as compojure]
            [ring.middleware.json :as ring-json]
            [puppetlabs.enterprise.file-sync-common :as common]
            [cheshire.core :as json]))

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

    * :working-dir  - The path under the Git server's data directory where the
                      repository's working tree resides."
  {:working-dir                                 schema/Str
   (schema/optional-key :http-push-enabled)   Boolean})

(def GitRepos
  {schema/Keyword GitRepo})

(def FileSyncServiceRawConfig
  "Schema defining the full content of the JGit service configuration.

  The keys should have the following values:

    * :base-path - The base path on the Git server under which all of the
                   repositories it is managing should reside.

    * :repos     - A vector with metadata about each of the individual
                   Git repositories that the server manages."
  {:base-path                               schema/Str
   :repos                                   GitRepos})

(def PublishRequestBody
  "Schema defining the body of a request to the publish content endpoint.

  The body is optional, but if supplied it must be a map with the
  following optional values:

    * :message - Commit message

    * :author  - Map containing :name and :email of author for commit "
  (schema/maybe
    {(schema/optional-key :message) schema/Str
     (schema/optional-key :author) {:name schema/Str
                                    :email schema/Str}}))

(def PublishError
  "Schema defining an error when attempting to publish a repo."
  {:error {:type (schema/enum ::publish-error
                              ::repo-not-found-error)
           :message schema/Str}})

(def PublishRepoResult
  "Schema defining the result of publishing a single repo, which is
  either a SHA for the new commit on the storage server, or an error."
  (schema/if map?
    PublishError
    schema/Str))

(def PublishResponseBody
  "Schema defining the body of a response to the publish content endpoint.

  The response is a map of repo name to repo status, which is either a
  SHA or an error map."
  {schema/Keyword PublishRepoResult})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn initialize-server-base-path!
  "Initialize the base path on the server under which all other git
  repositories will be hosted.  Expects, as an argument, a File representing
  the server base path.  Returns nil if initialization was successful or
  throws an Exception on failure."
  [server-base-path]
  {:pre  [(instance? File server-base-path)]
   :post [(nil? %)]}
  (try+
    (ks/mkdirs! server-base-path)
    (catch map? m
      (throw
        (Exception.
          (str "Problem occurred creating jgit server base-path: "
               (:message m)))))
    (catch Exception e
      (throw (Exception.
               (str "Problem occurred creating jgit server base-path: "
                    (.getMessage e)) e))))
  nil)

(defn initialize-repo!
  "Initialize a directory (specified by the `repo-path` parameter) for git
  repository content to be hosted on the server.   `repo-path` should be
  something that is coercible to a File.  If the `allow-anonymous-push?` flag
  is set, the 'http.receive-pack' setting in the git directory configuration to
  '1' so that the directory is exported for anonymous access.  Otherwise,
  http.receivepack will be set to 0, which disables all pushes to the repository
  over HTTP.  Returns nil."
  [repo-path allow-anonymous-push?]
  {:post [(nil? %)]}
  (doto
      ; On the server-side, the git repo has to be initialized
      ; as bare in order to be served up to remote clients
      (-> (InitCommand.)
          (.setDirectory (fs/file repo-path))
          (.setBare true)
          (.call)
          (.getRepository)
          (.getConfig))
    (.setInt "http" nil "receivepack" (if allow-anonymous-push? 1 0))
    (.save))
  nil)

(defn latest-commit-on-master
  "Returns the SHA-1 revision ID of the latest commit on the master branch of
   the repository specified by the given `git-dir`.  Returns `nil` if commits
   have been made on the repository."
  [git-dir]
  {:pre [(instance? File git-dir)]
   :post [(or (string? %) (nil? %))]}
  (when-let [ref (-> git-dir
                     (client/get-repository-from-git-dir)
                     (.getRef "refs/heads/master"))]
    (-> ref
        (.getObjectId)
        (client/commit-id))))

(defn compute-latest-commits
  "Computes the latest commit for each repository in `sub-paths`."
  [base-path sub-paths]
  (reduce
    (fn [acc sub-path]
      (let [repo-path (fs/file base-path (name sub-path))
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

(defn push-and-return-sha
  "Perform git push on git instance and return SHA of latest commit for
  updated remote."
  [git]
  {:pre [(instance? Git git)]}
  (-> git
      client/push
      first
      .getRemoteUpdates
      first
      .getNewObjectId
      .getName))

(schema/defn publish-repo :- PublishRepoResult
  "Add and commit all unversioned files and push to origin. Return the
  SHA of the commit if successful, or a map with an error message if
  not."
  [git :- Git
   sub-path :- schema/Str
   message :- schema/Str
   author :- PersonIdent]
  (try
    (log/debugf "Adding and commiting unversioned files for working directory %s" sub-path)
    (client/add-and-commit git message author)

    (log/debugf "Pushing working directory %s" sub-path)
    (push-and-return-sha git)))

(schema/defn publish-repos :- [PublishRepoResult]
  "Given a list of working directories, a commit message, and a commit
  author, perform an add, commit, and push for each working directory.
  Returns the newest SHA for each working directory that was
  successfully pushed, an error if there was no existing git repository
  in the directory, or an error that the add/commit/push failed."
  [sub-paths :- [schema/Str]
   message :- schema/Str
   author :- PersonIdent]
  (for [sub-path sub-paths]
    (do
      (log/infof "Publishing working directory %s to file sync storage service" sub-path)
      (try (let [git (-> sub-path
                         fs/file
                         Git/open)]
             (publish-repo git sub-path message author))
           (catch RepositoryNotFoundException e
             (log/errorf "Unable to find git repository %s" sub-path)
             {:error {:type ::repo-not-found-error
                      :message (.getMessage e)}})
           (catch GitAPIException e
             (log/errorf "Failed to publish %s: %s" sub-path (.getMessage e))
             {:error {:type ::publish-error
                      :message (str "Failed to publish " sub-path ":"
                                    (.getMessage e))}})))))

(schema/defn ^:always-validate publish-content :- PublishResponseBody
  "Given a map of repositories and the JSON body of the request, publish
  each working directory to the file sync storage service, using the
  contents of the body - if provided - for the commit author and
  message. Returns a map of repository name to status - either SHA of
  latest commit or error."
  [repos :- GitRepos
   body :- PublishRequestBody]
  (let [author (commit-author (:author body))
        message (:message body default-commit-message)
        new-commits (publish-repos (map :working-dir (vals repos)) message author)]
    (zipmap (keys repos) new-commits)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app
(defn build-routes
  "Builds the compojure routes from the given configuration values."
  [base-path repos]
  (compojure/routes
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
                             :body (publish-content repos json-body)})
                          (catch com.fasterxml.jackson.core.JsonParseException e
                            {:status 400
                             :body {:error {:type :json-parse-error
                                            :message "Could not parse body as JSON."}}}))
                        {:status 400
                         :body {:error {:type :content-type-error
                                        :message "Content type must be JSON."}}})))
    (compojure/ANY common/latest-commits-sub-path []
                   {:status 200
                    :body (compute-latest-commits base-path (keys repos))})))

(defn build-handler
  "Builds a ring handler from the given configuration values."
  [base-path sub-paths]
  (-> (build-routes base-path sub-paths)
      ringutils/wrap-request-logging
      ringutils/wrap-schema-errors
      ringutils/wrap-errors
      ring-json/wrap-json-response
      ringutils/wrap-response-logging))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate initialize-repos! :- nil
  "Initialize a vector of git repository directories on the server.  The
  repositories to initialize as well as the base directory under which the
  repositories should reside should be specified in the supplied config."
  [config :- FileSyncServiceRawConfig]
  (let [base-path (:base-path config)]
    (log/infof "Initializing file sync server base path: %s" base-path)
    (initialize-server-base-path! (fs/file base-path))
    (doseq [repo  (:repos config)]
      (let [repo-path (fs/file base-path (name (key repo)))
            http-push-enabled (:http-push-enabled (val repo))]
        (log/infof "Initializing file sync repository path: %s" repo-path)
        (initialize-repo! repo-path http-push-enabled))))
  nil)
