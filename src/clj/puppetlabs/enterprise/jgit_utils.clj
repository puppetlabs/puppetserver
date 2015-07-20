(ns puppetlabs.enterprise.jgit-utils
  (:import (org.eclipse.jgit.api Git ResetCommand$ResetType PullResult Status)
           (org.eclipse.jgit.lib PersonIdent RepositoryBuilder AnyObjectId
                                 Repository Ref)
           (org.eclipse.jgit.merge MergeStrategy)
           (org.eclipse.jgit.revwalk RevCommit)
           (org.eclipse.jgit.transport PushResult FetchResult)
           (org.eclipse.jgit.transport.http HttpConnectionFactory)
           (org.eclipse.jgit.util FS)
           (java.io File)
           (com.puppetlabs.enterprise HttpClientConnection)
           (org.eclipse.jgit.storage.file FileBasedConfig)
           (org.eclipse.jgit.api.errors NoHeadException))
  (:require [clojure.java.io :as io]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as schema]
            [me.raynes.fs :as fs]))

(schema/defn ^:always-validate create-connection :- HttpClientConnection
  [ssl-ctxt :- common/SSLContextOrNil
   url connection-proxy]
  (HttpClientConnection. ssl-ctxt url connection-proxy))

(schema/defn ^:always-validate create-connection-factory :- HttpConnectionFactory
  [ssl-ctxt :- common/SSLContextOrNil]
  (proxy [HttpConnectionFactory] []
    (create
      ([url]
        (create-connection ssl-ctxt (.toString url) nil))
      ([url connection-proxy]
        (create-connection ssl-ctxt (.toString url) connection-proxy)))))

(schema/defn identity->person-ident :- PersonIdent
  [{:keys [name email]} :- common/Identity]
  (PersonIdent. name email))

(schema/defn commit :- RevCommit
  "Perform a git-commit using the supplied message and author. Only files
  previously staged will be committed. If the commit is successful, a
  RevCommit is returned. If the commit failed, one of the following Exceptions
  from the org.eclipse.api.errors namespace may be thrown:

  * NoHeadException
    - when called on a git repo without a HEAD reference

  * UnmergedPathsException
    - when the current index contained unmerged paths (conflicts)

  * ConcurrentRefUpdateException
    - when HEAD or branch ref is updated concurrently be someone else

  * WrongRepositoryStateException
    - when repository is not in the right state for committing"
  [git :- Git
   message :- String
   identity :- common/Identity]
  (let [person-ident (identity->person-ident identity)]
    (-> git
      (.commit)
      (.setAll false) ; this is the default, but make it explicit
      (.setMessage message)
      (.setAuthor person-ident)
      (.setCommitter person-ident)
      (.call))))

(defn add!
  "Perform a git add with the given file pattern"
  [git file-pattern]
  (-> git
    (.add)
    (.addFilepattern file-pattern)
    (.call)))

(schema/defn add-and-commit :- RevCommit
  "Perform a git-add and git-commit of all files in the repo working tree. All
  files, whether previously indexed or not, will be considered for the commit.
  The supplied message and author will be attached to the commit.  If the commit
  is successful, a RevCommit is returned.  If the commit failed, one of the
  following Exceptions from the org.eclipse.api.errors namespace may be thrown:

  * NoHeadException
    - when called on a git repo without a HEAD reference

  * UnmergedPathsException
    - when the current index contained unmerged paths (conflicts)

  * ConcurrentRefUpdateException
    - when HEAD or branch ref is updated concurrently be someone else

  * WrongRepositoryStateException
    - when repository is not in the right state for committing"
  [git :- Git
   message :- String
   identity :- common/Identity]
  (add! git ".")
  (let [person-ident (identity->person-ident identity)]
    (-> git
        (.commit)
        (.setMessage message)
        (.setAll true)
        (.setAuthor person-ident)
        (.setCommitter person-ident)
        (.call))))

(defn delete-all-files-in-dir
  [dir]
  (doseq [f (fs/list-dir dir)]
    (fs/delete-dir (fs/file dir f))))

(defn clone
  "Perform a git-clone of the content at the specified 'server-repo-url' string
  into a local directory.  The 'local-repo-dir' parameter should be a value
  that can be coerced into a File by `clojure.java.io/as-file`.

  The implementation currently hardcodes an 'origin' remote and 'master' branch as
  the content to be cloned.  If the clone is successful, a handle to a Git
  instance which wraps the repository is returned.  If the clone failed, one of
  the following Exceptions from the org.eclipse.jgit.api.errors namespace may be
  thrown:

  * InvalidRemoteException
    - when an invalid `server-repo-url` was provided (e.g., not syntactically
      valid as a URL).

  * TransportException -
    - when a protocol error occurred during fetching of objects (e.g., an
      inability to connect to the server or if the repository in the URL was
      not accessible on the server)

  * GitAPIException
    - when some other low-level Git failure occurred"
  ([server-repo-url local-repo-dir]
   (clone server-repo-url local-repo-dir false))
  ([server-repo-url local-repo-dir bare?]
   {:pre [(string? server-repo-url)]
    :post [(instance? Git %)]}
   (let [pre-existing-bare-repo-dir? (and bare? (fs/exists? local-repo-dir))
         clone-command (.. (Git/cloneRepository)
                           (setURI server-repo-url)
                           (setDirectory (io/as-file local-repo-dir))
                           (setBare bare?)
                           (setRemote "origin")
                           (setBranch "master"))]
     (try
       (.call clone-command)
       (catch Throwable t
         ; Don't leave a bogus git repository behind, this can prevent future
         ; attempts to clone into this directory from succeeding.
         (if bare?
           (if pre-existing-bare-repo-dir?
             (delete-all-files-in-dir local-repo-dir)
             (fs/delete-dir local-repo-dir))
           (fs/delete-dir (fs/file local-repo-dir ".git")))
         (throw t))))))

(defn fetch
  "Perform a git-fetch of remote commits into the supplied repository.
  Returns a FetchResult or throws one of the following Exceptions from the
  org.eclipse.jgit.api.errors package:

  * InvalidRemoteException
  * TransportException
  * GitAPIException"
  [repo]
  {:pre [(instance? Repository repo)]
   :post [(instance? FetchResult %)]}
  (-> repo
      (Git.)
      (.fetch)
      (.setRemote "origin")
      (.call)))

(schema/defn ^:always-validate
  hard-reset :- Ref
  "Perform a hard git-reset of the provided repo. Returns a Ref or
  throws a GitAPIException from the org.eclipse.jgit.api.errors
  package."
  [repo :- Repository]
  (.. (Git. repo)
      (reset)
      (setMode ResetCommand$ResetType/HARD)
      (call)))

(defn pull
  "Perform a git-pull of remote commits into the supplied repository.  Does not
  do a rebase of current content on top of the new content being fetched.  Uses
  a merge strategy of 'THEIRS' to defer to give remote content precedence over
  any local content in the event of a merge conflict.  Returns a PullResult or
  throws one of the following Exceptions from the org.eclipse.api.errors
  package:

  * WrongRepositoryStateException
  * InvalidConfigurationException
  * DetachedHeadException
  * InvalidRemoteException
  * CanceledException
  * RefNotFoundException
  * TransportException
  * GitAPIException"
  [repo]
  {:pre [(instance? Repository repo)]
   :post [(instance? PullResult %)]}
  (-> repo
      (Git.)
      (.pull)
      (.setRebase false)
      (.setStrategy MergeStrategy/THEIRS)
      (.call)))

(defn push
  "Perform a git-push of pending commits in the supplied repository to a
  remote, if specified. If no remote is specified, follows the
  repository's configuration or the push default configuration. Returns
  an iteration over PushResult objects or may throw one of the following
  Exceptions from the org.eclipse.jgit.api.errors namespace:

  * TransportException -
    - when a protocol error occurred during fetching of objects (e.g., an
      inability to connect to the server or if the repository in the URL was
      not accessible on the server)

  * GitAPIException
    - when some other low-level Git failure occurred"
  ([git]
   {:pre [(instance? Git git)]
    :post [(instance? Iterable %)
           (every? #(instance? PushResult %) %)]}
   (-> git
       (.push)
       (.call)))
  ([git remote]
   {:pre [(instance? Git git)
          (string? remote)]
    :post [(instance? Iterable %)
           (every? #(instance? PushResult %) %)]}
   (-> git
       (.push)
       (.setRemote remote)
       (.call))))

(defn commit-id
  "Given an instance of `AnyObjectId` or its subclasses
  (for example, a `RevCommit`) return the SHA-1 ID for that commit."
  [commit]
  {:pre [(instance? AnyObjectId commit)]
   :post [(string? %)]}
  ; This just exists because the JGit API is stupid.
  (.name commit))

(schema/defn get-repository :- Repository
  "Given a path to a Git repository and a working tree, return a Repository instance."
  [repo working-tree]
  (.. (RepositoryBuilder.)
      (setGitDir (io/as-file repo))
      (setWorkTree (io/as-file working-tree))
      (build)))

(schema/defn get-repository-from-working-tree :- (schema/maybe Repository)
  "Given a path to a working tree, return a Repository instance,
   or nil if no repository exists in $path/.git."
  [path]
  (if-let [repo (.. (RepositoryBuilder.)
                    (setWorkTree (io/as-file path))
                    (build))]
    (when (.. repo
              (getObjectDatabase)
              (exists))
      repo)))

(schema/defn get-repository-from-git-dir :- (schema/maybe Repository)
  "Given a path to a Git repository (i.e., a .git directory) return a
   Repository instance, or nil if no repository exists at path."
  [path]
  (if-let [repo (.. (RepositoryBuilder.)
                    (setGitDir (io/as-file path))
                    (build))]
    (when (.. repo
              (getObjectDatabase)
              (exists))
      repo)))

(defn head-rev-id
  "Returns the SHA-1 revision ID of a repository on disk.  Like
  `git rev-parse HEAD`.  Returns `nil` if the repository has no commits."
  [repo]
  {:pre [(instance? Repository repo)]
   :post [(or (nil? %) (string? %))]}
  (if-let [commit (.resolve repo "HEAD")]
    (commit-id commit)))

(defn head-rev-id-from-working-tree
  "Given a file or a string path to a file, returns the SHA-1 revision
  ID of a repository on disk, using the file as the working tree.  Like
  `git rev-parse HEAD`.  Returns `nil` if the repository has no
  commits."
  [repo]
  {:pre [((some-fn #(instance? File %)
                   string?)
           repo)]
   :post [(or (nil? %) (string? %))]}
  (if-let [as-repo (get-repository-from-working-tree (io/as-file repo))]
    (head-rev-id as-repo)))

(defn head-rev-id-from-git-dir
  "Given a file or a string path to a file, returns the SHA-1 revision
  ID of a repository on disk, using the file as the git dir.  Like `git
  rev-parse HEAD`.  Returns `nil` if the repository has no commits."
  [repo]
  {:pre [((some-fn #(instance? File %)
                   string?)
           repo)]
   :post [(or (nil? %) (string? %))]}
  (if-let [as-repo (get-repository-from-git-dir (io/as-file repo))]
    (head-rev-id as-repo)))

(schema/defn latest-commit :- (schema/maybe RevCommit)
  "Returns the latest commit of repo on its current branch.  Like 'git log -n 1'."
  [repo :- Repository]
  (when (head-rev-id repo)
    (-> repo
      Git/wrap
      .log
      (.setMaxCount 1)
      .call
      first)))

(schema/defn submodules-status
  "Like 'git submodule status'."
  [repo :- Repository]
  (-> repo
      Git/wrap
      .submoduleStatus
      .call))

(defn get-submodules-latest-commits
  "Given a path to a Git repository and a working tree, returns the
  latest commit for all submodules in that repo"
  [git-dir working-dir]
  (let [submodule-status (submodules-status (get-repository git-dir working-dir))]
    (ks/mapvals (fn [v] (.getName (.getIndexId v))) submodule-status)))

(defn get-submodules
  "Given a path to a Git repository and a working tree, returns the
  list of all submodules in that repo"
  [repo]
  (keys (submodules-status repo)))

(defn fetch-submodules!
  "Given a path to a Git repository and a working tree, perform a
  fetch for all submodules registered for that repo. This is required
  due to JGit Bug 470318 (https://bugs.eclipse.org/bugs/show_bug.cgi?id=470318),
  wherein JGit won't perform a fetch on any submodules before attempting to
  checkout the relevant commit for the submodule."
  [repo]
  (let [submodules (get-submodules repo)
        work-tree (.getWorkTree repo)]
    (doseq [submodule submodules]
      (when-let [submodule-repo (get-repository-from-working-tree
                                  (fs/file work-tree submodule))]
        (fetch submodule-repo)))))

(defn submodules-config
  "Given a Git repository, return its .gitmodules file as a
  FileBasedConfig."
  [repo]
  (let [submodules-file (fs/file (.getWorkTree repo) ".gitmodules")]
    (FileBasedConfig. submodules-file FS/DETECTED)))

(defn remove-submodule-configuration!
  "Given a repository and a submodule name, remove that submodule from
  the repository's configuration."
  [repo submodule-name]
  (let [parent-config (.getConfig repo)
        gitmodules (submodules-config repo)]
    (.load gitmodules)
    (.unsetSection gitmodules "submodule" submodule-name)
    (.save gitmodules)
    (.unsetSection parent-config "submodule" submodule-name)
    (.save parent-config)))

(defn remove-submodule!
  "Given a repository and a submodule name, remove that submodule from
  the repository"
  [repo submodule-name]
  (remove-submodule-configuration! repo submodule-name)
  (let [git (Git. repo)]
    (-> git
      .rm
      (.setCached true)
      (.addFilepattern submodule-name)
      .call))
  (fs/delete-dir (fs/file (.getWorkTree repo) submodule-name)))

(defn submodule-add!
  "Given a git-wrapped repository, a path, and a URL, add the submodule
   at the given path to the provided repo"
  [git submodule-path submodule-url]
  (let [work-tree (.getWorkTree (.getRepository git))
        submodule-absolute-path (if (fs/absolute? submodule-path)
                                  submodule-path
                                  (fs/file work-tree submodule-path))
        pre-existing-submodule-path? (fs/exists? submodule-absolute-path)
        submodule-add-command (.. git
                                  submoduleAdd
                                  (setPath submodule-path)
                                  (setURI submodule-url))]
    (try
      (.call submodule-add-command)
      (catch Throwable t
        ; Don't leave a bogus submodule behind, this can
        ; prevent future attempts to add this submodule from succeeding.
        (when-not pre-existing-submodule-path?
          (fs/delete-dir submodule-absolute-path))
        (throw t)))))

(defn change-submodule-url!
  "Given a repository, a submodule name, and a file path to a repository,
  changes the url for the given submodule in the given repository to the
  file path provided. This is used to ensure that, when syncing a
  client's repository into a working directory, the repo's submodules
  are cloned and fetched from the locally synced bare repositories for
  those submodules rather than from the original bare repositories for
  those submodules exposed by the storage service."
  [repo submodule-name remote]
  (let [parent-config (.getConfig repo)]
    (.setString parent-config "submodule" submodule-name "url" remote)
    (.save parent-config)))

(schema/defn ^:always-validate submodule-update
  "Perform a git submodule init, and then a subsequent git submodule update
  to update all submodules. Returns a collection of strings representing updated
  submodule paths or may throw one of the following exceptions from the
  org.eclipse.jgit.api.errors namespace:

  * ConcurrentRefUpdateException
  * CheckoutConflictException
  * InvalidMergeHeadsException
  * InvalidConfigurationException
  * NoHeadException
  * NoMessageException
  * RefNotFoundException
  * WrongRepositoryStateException
  * GitAPIException"
  [repo :- Repository]
  (let [git (Git. repo)]
    (-> git
        (.submoduleInit)
        (.call))
    (fetch-submodules! repo)
    (-> git
        (.submoduleUpdate)
        (.call))))

(schema/defn status :- Status
  "Like 'git status'"
  [repo :- Repository]
  (-> repo
      Git/wrap
      .status
      .call))

(defn extract-submodule-name
  [submodule]
  (re-find #"[^\/]+$" submodule))

(schema/defn commit->status-info
  "Given a RevCommit, extracts and returns information about it which is
   relevant for the /status endpoint."
  [commit :- RevCommit]
  {:commit (commit-id commit)
   :date (common/jgit-time->human-readable (.getCommitTime commit))
   :message (.getFullMessage commit)
   :author {:name (.getName (.getAuthorIdent commit))
            :email (.getEmailAddress (.getAuthorIdent commit))}})

(defn repo-status-info
  [repo]
  (let [repo-status (status repo)]
    {:clean (.isClean repo-status)
     :modified (.getModified repo-status)
     :missing (.getMissing repo-status)
     :untracked (.getUntracked repo-status)}))

(defn submodules-status-info
  [repo]
  (->> repo
    submodules-status
    (ks/mapvals
      (fn [ss]
        {:path (.getPath ss)
         :status (.toString (.getType  ss))
         :head-id (commit-id (.getHeadId ss))}))))
