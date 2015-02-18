(ns puppetlabs.enterprise.file-sync-test-utils
  (:import (org.eclipse.jgit.api Git)
           (org.eclipse.jgit.transport RemoteRefUpdate$Status)
           (org.eclipse.jgit.treewalk.filter PathFilter)
           (org.eclipse.jgit.treewalk TreeWalk)
           (org.eclipse.jgit.lib PersonIdent))
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.jgit-client :as jgit-client]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.kitchensink.core :as ks]))

(def default-api-path-prefix "/file-sync")

(def default-repo-path-prefix "/git")

(def http-port                    8080)

(def file-text                    "here is some text")

(def server-base-url              (str "http://localhost:" http-port))

(def server-repo-url              (str server-base-url default-repo-path-prefix))

(def author                       (PersonIdent.
                                    "lein tester" "lein.tester@bogus.com"))

(defn repo-base-url
  ([] (repo-base-url default-repo-path-prefix))
  ([repo-path-prefix]
   (str server-base-url repo-path-prefix)))

(defn webserver-plaintext-config
  []
  {:webserver {:port http-port}
   :web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                         {:api          default-api-path-prefix
                          :repo-servlet default-repo-path-prefix}}})

(defn enable-push
  "Given the config map for a repo, return an updated config map that
  enables anonymous push access on it."
  [repo]
  (assoc repo :http-push-enabled true))

(defn file-sync-storage-config-payload
  "Enables anonymous push access on each repo for ease of testing."
  [base-path repos]
  {:base-path base-path
   :repos     (map enable-push repos)})

(defn file-sync-storage-config
  [base-path repos]
  {:file-sync-storage (file-sync-storage-config-payload base-path repos)})

(defn jgit-plaintext-config-with-repos
  [base-path repos]
  (merge (webserver-plaintext-config)
         (file-sync-storage-config base-path repos)))

(defn temp-dir-as-string
  []
  (.getPath (ks/temp-dir)))

(defn write-test-file
  [file]
  (spit file file-text))

(defmacro with-bootstrapped-file-sync-storage-service-for-http
  [app config & body]
  `(bootstrap/with-app-with-config
     ~app
     [webrouting-service/webrouting-service file-sync-storage-service/file-sync-storage-service
      jetty9-service/jetty9-service]
     ~config
     (do
       ~@body)))

(defn clone-and-validate
  [server-repo-url local-repo-dir]
  (let [local-repo (jgit-client/clone server-repo-url local-repo-dir)]
    (is (not (nil? local-repo))
        (format "Repository cloned from server (%s) to (%s) should be non-nil"
                server-repo-url
                local-repo-dir))
    local-repo))

(defn validate-files-in-commit
  [repo rev-commit expected-names-of-files-committed message]
  (let [tree      (.getTree rev-commit)
        tree-walk (doto (TreeWalk. repo)
                    (.addTree tree))]
    (doseq [expected-name-of-file-committed expected-names-of-files-committed]
      (doto tree-walk
        (.reset tree)
        (.setFilter (PathFilter/create expected-name-of-file-committed)))
      (is (.next tree-walk)
          (format "Unable to find file (%s) in tree for commit with message: %s"
                  expected-name-of-file-committed
                  message)))))

(defn commit-and-validate
  [repo message author expected-names-of-files-committed]
  (let [rev-commit    (jgit-client/add-and-commit repo message author)
        commit-author (.getAuthorIdent rev-commit)]
    (is (= message (.getFullMessage rev-commit))
        "Unexpected message stored on the commit")
    (is (= (.getName author) (.getName commit-author))
        (format "Unexpected author name stored on the commit with message: %s"
                message))
    (is (= (.getEmailAddress author) (.getEmailAddress commit-author))
        (str "Unexpected author e-mail address stored on the commit"
             "with message: "
             message))
    (validate-files-in-commit (.getRepository repo)
                              rev-commit
                              expected-names-of-files-committed
                              message)
    rev-commit))

(defn push-and-validate
  [repo message expected-latest-commit]
  (let [results (jgit-client/push repo)]
    (is (= 1 (count results))
        (format "Unexpected number of results for push for: %s"
                message))
    (let [refs (.getRemoteUpdates (first results))]
      (is (= 1 (count refs))
          (format "Unexpected number of refs for remote update for: %s"
                  message))
      (let [ref (first refs)]
        (is (= (RemoteRefUpdate$Status/OK) (.getStatus ref))
            (format "Unexpected status for ref for: %s"
                    message))
        (is (= (.getName expected-latest-commit)
               (.getName (.getNewObjectId ref)))
            (format "Unexpected object id for last commit pushed for: %s"
                    message))))
    results))

(defn commit-and-push-files
  [repo message author files]
  (push-and-validate repo
                     message
                     (commit-and-validate repo message author files)))

(defn create-and-push-file
  ([repo-dir]
   (create-and-push-file
     (Git. (jgit-client/get-repository (fs/file repo-dir)))
     repo-dir))
  ([repo repo-dir]
   (create-and-push-file repo repo-dir (str "test-file" (ks/uuid))))
  ([repo repo-dir file]
    (write-test-file (str repo-dir "/" file))
    (commit-and-push-files repo
                           "update via lein test run"
                           author
                           [file])))

(defn clone-repo-and-push-test-files
  "Clones the repository specified by `server-repo-subpath` through the JGit
   service.  Creates the specified number of new files on disk (or just one, if
   no number is specified) and adds them to the repository,  commits the
   changes, and pushes the commit to the server.  Returns the path on disk
   to the repository."
  ([server-repo-subpath]
   (clone-repo-and-push-test-files server-repo-subpath 1))
  ([server-repo-subpath number-of-files]
   (let [client-repo-dir  (ks/temp-dir)
         server-repo-url  (str (repo-base-url) "/" server-repo-subpath)
         client-orig-repo (jgit-client/clone server-repo-url client-repo-dir)]
     (dotimes [_ number-of-files]
       (create-and-push-file client-orig-repo client-repo-dir))
     client-repo-dir)))

(defn init-repo!
  [path]
  "Creates a new Git repository at the given path.  Like `git init`."
  (-> (Git/init)
      (.setDirectory path)
      (.call)))