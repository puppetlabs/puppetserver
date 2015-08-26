(ns puppetlabs.enterprise.file-sync-test-utils
  (:import (org.eclipse.jgit.api Git)
           (org.eclipse.jgit.transport HttpTransport)
           (org.eclipse.jgit.transport.http JDKHttpConnectionFactory)
           (org.eclipse.jgit.treewalk CanonicalTreeParser)
           (org.eclipse.jgit.lib PersonIdent))
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [puppetlabs.enterprise.jgit-utils :as jgit-utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :as webrouting-service]
            [puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service :as file-sync-storage-service]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-service :as file-sync-client-service]
            [puppetlabs.enterprise.services.file-sync-client.file-sync-client-core :as file-sync-client-core]
            [puppetlabs.enterprise.file-sync-common :as common]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :as scheduler-service]
            [puppetlabs.trapperkeeper.services.status.status-service :as status-service]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9-service]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.ssl-utils.core :as ssl]
            [clj-time.format :as time-format]))

(def default-api-path-prefix "/file-sync")

(def default-repo-path-prefix "/file-sync-git")

(def http-port 8080)

(def https-port 10080)

(def file-text "here is some text")

(def server-base-url (str "http://localhost:" http-port))

(def server-base-url-ssl (str "https://localhost:" https-port))

(def server-repo-url (str server-base-url default-repo-path-prefix))

(def test-commit-message
  "update via test")

(def test-person-ident
  (PersonIdent. "Tester Testypants" "tester@bogus.com"))

(defn base-url
  ([]
   (base-url false))
  ([ssl?]
   (if ssl?
     server-base-url-ssl
     server-base-url)))

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
                             (file-sync-client-core/create-connection-factory ssl-context)
                             (JDKHttpConnectionFactory.))]
    (HttpTransport/setConnectionFactory connection-factory)))

(def webserver-base-config
  {:web-router-service {:puppetlabs.enterprise.services.file-sync-storage.file-sync-storage-service/file-sync-storage-service
                        {:api default-api-path-prefix
                         :repo-servlet default-repo-path-prefix}
                        :puppetlabs.trapperkeeper.services.status.status-service/status-service "/status"}})

(defn webserver-plaintext-config
  ([]
    (webserver-plaintext-config http-port))
  ([port]
   (assoc webserver-base-config
     :webserver {:port port})))

(defn webserver-ssl-config
  ([]
    (webserver-ssl-config https-port))
  ([port]
   (assoc webserver-base-config
     :webserver (merge {:ssl-port port
                        :ssl-host "0.0.0.0"} ssl-options))))

(defn storage-service-config
  ([data-dir repos]
   (storage-service-config data-dir repos false))
  ([data-dir repos ssl?]
   (assoc (if ssl? (webserver-ssl-config)
                   (webserver-plaintext-config))
     :file-sync-common {:server-url (base-url ssl?)
                        :data-dir data-dir}
     :file-sync-storage {:repos repos})))

(defn client-service-config
  ([data-dir]
    (client-service-config data-dir false))
  ([data-dir ssl?]
   (assoc (if ssl? (webserver-ssl-config 9000)
                   (webserver-plaintext-config 8000 ))
     :file-sync-common {:server-url (base-url ssl?)
                        :data-dir data-dir}
     :file-sync-client (assoc (if ssl? ssl-options {})
                         :poll-interval 1
                         :server-api-path (str default-api-path-prefix "/v1")
                         :server-repo-path default-repo-path-prefix))))

(defn file-sync-config
  ([data-dir repos]
    (file-sync-config data-dir repos false))
  ([data-dir repos ssl?]
   (assoc (if ssl? (webserver-ssl-config) (webserver-plaintext-config))
     :file-sync-storage {:repos repos}
     :file-sync-common {:server-url (base-url ssl?)
                        :data-dir data-dir}
     :file-sync-client (assoc (if ssl? ssl-options {})
                         :poll-interval 1
                         :server-api-path (str default-api-path-prefix "/v1")
                         :server-repo-path default-repo-path-prefix))))

(defn temp-dir-as-string
  []
  (.getPath (ks/temp-dir)))

(defn write-test-file!
  [file]
  (spit file file-text))

(def storage-service-and-deps
  [webrouting-service/webrouting-service
   file-sync-storage-service/file-sync-storage-service
   jetty9-service/jetty9-service
   status-service/status-service])

(def client-service-and-deps
  [jetty9-service/jetty9-service
   webrouting-service/webrouting-service
   status-service/status-service
   file-sync-client-service/file-sync-client-service
   scheduler-service/scheduler-service])

(def file-sync-services-and-deps
  (concat storage-service-and-deps client-service-and-deps))

(defmacro with-bootstrapped-storage-service
  [app config & body]
  `(bootstrap/with-app-with-config
     ~app
     storage-service-and-deps
     ~config
     (do
       ~@body)))

(defmacro with-bootstrapped-client-service-and-webserver
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
       client-service-and-deps
       ~client-config
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
   (with-open [repo (Git. (jgit-utils/get-repository-from-working-tree (fs/file repo-path)))]
     (jgit-utils/add-and-commit repo test-commit-message test-person-ident)
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
      (.setDirectory (io/as-file path))
      (.setBare true)
      (.call)))

(defn get-latest-commit-diff
  [repo]
  (let [reader* (.newObjectReader repo)]
    (-> repo
      Git.
      .diff
      (.setNewTree (doto (CanonicalTreeParser.)
                     (.reset reader* (.resolve repo "HEAD^{tree}"))))
      (.setOldTree (doto (CanonicalTreeParser.)
                     (.reset reader* (.resolve repo "HEAD~1^{tree}"))))
      .call)))

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

(defn parse-timestamp
  [timestamp]
  (time-format/parse common/datetime-formatter timestamp))

(def latest-commits-url (str server-base-url
                          default-api-path-prefix
                          "/v1"
                          common/latest-commits-sub-path))

(defn latest-commits-response
  []
  (http-client/post latest-commits-url {:as :text}))

(defn get-latest-commits
  []
  (file-sync-client-core/get-body-from-latest-commits-payload
    (latest-commits-response)))

(defn get-latest-commits-for-repo
  [repo]
  (get-in (get-latest-commits) [(keyword repo) :commit]))

(def publish-url
  (str server-base-url
    default-api-path-prefix
    "/v1"
    common/publish-content-sub-path))

(defn do-publish
  ([]
   (do-publish nil))
  ([body]
   (http-client/post
     publish-url
     {:as :text
      :headers {"content-type" "application/json"}
      :body body})))

(defn get-client-status
  "Makes an HTTP request to the Client Service's /status endpoint."
  ([]
   (get-client-status nil))
  ([level]
   (http-client/get
     (str server-base-url
       (if level
         (str "/status/v1/services/file-sync-client-service?level=" (name level))
         "/status/v1/services/file-sync-client-service"))
     {:as :text})))
