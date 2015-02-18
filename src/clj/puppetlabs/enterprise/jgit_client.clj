(ns puppetlabs.enterprise.jgit-client
  (:import (org.eclipse.jgit.api Git PullResult)
           (org.eclipse.jgit.lib PersonIdent RepositoryBuilder AnyObjectId
                                 Repository)
           (org.eclipse.jgit.merge MergeStrategy)
           (org.eclipse.jgit.revwalk RevCommit)
           (org.eclipse.jgit.transport PushResult)
           (org.eclipse.jgit.transport.http HttpConnectionFactory)
           (java.io File)
           (com.puppetlabs.enterprise HttpClientConnection))
  (:require [clojure.java.io :as io]
            [puppetlabs.enterprise.file-sync-common :as common]
            [schema.core :as schema]))

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

(defn add-and-commit
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
  [git message author]
  {:pre [(instance? Git git)
         (string? message)
         (instance? PersonIdent author)]
   :post [(instance? RevCommit %)]}
  (-> git
      (.add)
      (.addFilepattern ".")
      (.call))
  (-> git
      (.commit)
      (.setMessage message)
      (.setAll true)
      (.setAuthor author)
      (.call)))

(defn clone
  "Perform a git-clone of the content at the specified 'server-repo-url' string
  into a local directory.  The 'local-repo-dir' parameter should be a value
  that can be coerced into a File by `clojure.java.io/as-file`.

  The implementation currently hardcodes an 'origin' remote and 'master' branch as
  the content to be cloned.  If the clone is successful, a handle to a Git
  instance which wraps the repository is returned.  If the clone failed, one of
  the following Exceptions from the org.eclipse.api.errors namespace may be
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
  [server-repo-url local-repo-dir]
  {:pre [(string? server-repo-url)]
   :post [(instance? Git %)]}
  (-> (Git/cloneRepository)
      (.setURI server-repo-url)
      (.setDirectory (io/as-file local-repo-dir))
      (.setRemote "origin")
      (.setBranch "master")
      (.call)))

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
  "Perform a git-push of pending commits to the supplied repository.  Returns
  an iteration over PushResult objects or may throw one of the following
  Exceptions from the org.eclipse.api.errors namespace:

  * TransportException -
    - when a protocol error occurred during fetching of objects (e.g., an
      inability to connect to the server or if the repository in the URL was
      not accessible on the server)

  * GitAPIException
    - when some other low-level Git failure occurred"
  [git]
  {:pre [(instance? Git git)]
   :post [(instance? Iterable %)
          (every? #(instance? PushResult %) %)]}
  (-> git
      (.push)
      (.call)))

(defn commit-id
  "Given an instance of `AnyObjectId` or its subclasses
  (for example, a `RevCommit`) return the SHA-1 ID for that commit."
  [commit]
  {:pre [(instance? AnyObjectId commit)]
   :post [(string? %)]}
  ; This just exists because the JGit API is stupid.
  (.name commit))

(defn get-repository
  "Get a JGit Repository object for the supplied directory.  Returns nil if
  no repository exists at the supplied directory."
  [repo-dir]
  {:pre [(instance? File repo-dir)]
   :post [(or (nil? %) (instance? Repository %))]}
  (if-let [repo (-> (RepositoryBuilder.)
                    (.setWorkTree repo-dir)
                    (.build))]
    (if (-> repo
            (.getObjectDatabase)
            (.exists))
      repo
      nil)))

(defn head-rev-id
  "Returns the SHA-1 revision ID of a repository on disk.  Like
  `git rev-parse HEAD`.  Returns `nil` if the repository has no commits."
  [repo]
  {:pre [((some-fn #(instance? File %)
                   #(instance? Repository %)
                   string?)
          repo)]
   :post [(or (nil? %) (string? %))]}
  (if-let [as-repo (if (instance? Repository repo)
                     repo
                     (get-repository (io/as-file repo)))]
    (if-let [commit (.resolve as-repo "HEAD")]
      (commit-id commit))))


