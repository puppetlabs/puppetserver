(ns puppetlabs.enterprise.file-sync-test-utils
  (:import (org.eclipse.jgit.api Git)
           (org.eclipse.jgit.lib PersonIdent)
           (org.eclipse.jgit.transport HttpTransport)
           (org.eclipse.jgit.transport.http JDKHttpConnectionFactory))
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service :as file-sync-storage-service]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service :as file-sync-client-service]
            [puppetlabs.enterprise.services.scheduler.scheduler-service :as scheduler-service]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.ssl-utils.core :as ssl]))

(def default-api-path-prefix "/file-sync")

(def default-repo-path-prefix "/git")

(def http-port 8080)

(def https-port 10080)

(def file-text "here is some text")

(def server-base-url (str "http://localhost:" http-port))

(def server-base-url-ssl (str "https://localhost:" https-port))

(def server-repo-url (str server-base-url default-repo-path-prefix))

(def author (PersonIdent.
              "lein tester" "lein.tester@bogus.com"))

(defn base-url
  [ssl?]
  (if ssl?
    server-base-url-ssl
    server-base-url))

(defn repo-base-url
  ([] (repo-base-url default-repo-path-prefix false))
  ([ssl?] (repo-base-url default-repo-path-prefix ssl?))
  ([repo-path-prefix ssl?]
    (let [base-url (if ssl?
                     server-base-url-ssl
                     server-base-url)]
      (str base-url repo-path-prefix))))

(def ssl-options
  {:ssl-ca-cert "./dev-resources/ssl/ca.pem"
   :ssl-cert    "./dev-resources/ssl/cert.pem"
   :ssl-key     "./dev-resources/ssl/key.pem"})

; Used to configure JGit for SSL in tests
(def ssl-context
  (ssl/generate-ssl-context ssl-options))

(defn configure-JGit-SSL!
  [ssl?]
  (let [connection-factory (if ssl?
                             (jgit-utils/create-connection-factory ssl-context)
                             (JDKHttpConnectionFactory.))]
    (HttpTransport/setConnectionFactory connection-factory)))

(def webserver-plaintext-config
  {:webserver {:port http-port}
   :web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                         {:api          default-api-path-prefix
                          :repo-servlet default-repo-path-prefix}}})

(def webserver-ssl-config
  {:webserver          (merge {:ssl-port https-port
                               :ssl-host "0.0.0.0"} ssl-options)
   :web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                        {:api          default-api-path-prefix
                         :repo-servlet default-repo-path-prefix}}})

(defn file-sync-storage-config
  [data-dir repos]
  {:file-sync-storage {:data-dir data-dir
                       :repos    repos}})

(defn storage-service-config-with-repos
  [data-dir repos ssl?]
  (merge (if ssl? webserver-ssl-config webserver-plaintext-config)
         (file-sync-storage-config data-dir repos)))

(defn file-sync-client-config-payload
  [repos ssl?]
  (let [ssl-opts (if ssl? ssl-options {})]
    (merge ssl-opts
           {:server-url       (base-url ssl?)
            :poll-interval    1
            :server-api-path  default-api-path-prefix
            :server-repo-path default-repo-path-prefix
            :repos            repos})))

(defn client-service-config-with-repos
  [repos ssl?]
  {:file-sync-client (file-sync-client-config-payload repos ssl?)})

(defn temp-dir-as-string
  []
  (.getPath (ks/temp-dir)))

; TODO use this function from kitchensink once it's available in a release
(defn temp-file-name
  "Returns a unique name to a temporary file, but does not actually create the file."
  [file-name-prefix]
  (fs/file (fs/tmpdir) (fs/temp-name file-name-prefix)))

(defn write-test-file!
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

(defmacro with-bootstrapped-file-sync-client-and-webserver
  [app webserver-config ring-handler client-config & body]
  `(bootstrap/with-app-with-config
     webserver-app#
     [jetty9-service/jetty9-service]
     ~webserver-config
     (let [target-webserver# (tk-app/get-service webserver-app# :WebserverService)]
       (jetty9-service/add-ring-handler
         target-webserver#
         ~ring-handler
         "/"))
     (bootstrap/with-app-with-config
       client-app#
       [file-sync-client-service/file-sync-client-service
        scheduler-service/scheduler-service]
       {:file-sync-client ~client-config}
       (let [~app client-app#]
         (do
           ~@body)))))

(defn clone-from-data-dir
  [data-dir repo-id path]
  (let [repo-url (str "file://" data-dir "/" repo-id ".git")]
    (jgit-utils/clone repo-url path)))

(defn push-test-commit!
  "Given a path on disk to Git repository, creates a test file in that repo,
  adds it, commits it, and pushes it
  (via 'jgit-utils/push' with no remote specified.)"
  ([repo-path]
   (push-test-commit! repo-path (str "test-file" (ks/uuid))))
  ([repo-path file-name]
   (write-test-file! (str repo-path "/" file-name))
   (let [repo (Git. (jgit-utils/get-repository-from-working-tree (fs/file repo-path)))]
     (jgit-utils/add-and-commit repo "update via test" author)
     (jgit-utils/push repo))))

(defn clone-and-push-test-commit!
  "Clones the specified repo, pushes a test commit, and returns the directory
  to which the repo was cloned."
  ([repo-id data-dir]
    (clone-and-push-test-commit! repo-id data-dir nil))
  ([repo-id data-dir file-name]
   (let [repo-dir (fs/temp-dir repo-id)]
     (clone-from-data-dir data-dir repo-id repo-dir)
     (if file-name
       (push-test-commit! repo-dir file-name)
       (push-test-commit! repo-dir))
     repo-dir)))

(defn init-repo!
  "Creates a new Git repository at the given path.  Like `git init`."
  [path]
  (-> (Git/init)
      (.setDirectory path)
      (.call)))

(defn init-bare-repo!
  "Creates a new Git repository at the given path.  Like `git init`."
  [path]
  (-> (Git/init)
      (.setDirectory path)
      (.setBare true)
      (.call)))

(defn add-remote!
  "Adds a remote named `name` with url `url` to a git instance."
  [git name url]
  (let [config (-> git
                   .getRepository
                   .getConfig)]
    (.setString config "remote" name "url" url)
    (.save config)))

(defn add-watch-and-deliver-new-state
  "Given a agent/atom/ref/var and a promise, add a watch to ref* and deliver the
   new state to promise* the first time the watch is triggered.
   The watch will be removed after it's triggered."
  [ref* promise*]
  (let [key* (keyword (str "test-watcher-" (System/currentTimeMillis)))]
    (add-watch
      ref*
      key*
      (fn [k _ old-state new-state]
        (when (= key* k)
          (deliver promise* new-state)
          (remove-watch ref* k))))))

(defn wait-for-new-state
  "Given a agent/atom/ref/var, add a watch and return the new state the first
   time the watch is triggered.  The watch will be removed after it's triggered."
  [ref*]
  (let [promise* (promise)]
    (add-watch-and-deliver-new-state ref* promise*)
    (deref promise*)))

(defn get-sync-agent [app]
  (->> :FileSyncClientService
       (tk-app/get-service app)
       (tk-services/service-context)
       :agent))
