(ns puppetlabs.enterprise.file-sync-common
  (:require [schema.core :as schema]
            [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Url paths

(def latest-commits-sub-path "/latest-commits")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def LatestCommitsPayload
  "Schema defining the return payload of the server's 'latest-commits'
  endpoint.

  The first Str in each pair represents a repository name.  The corresponding
  Str in the pair represents the id of the latest commit in the repository."
  {schema/Str (schema/maybe schema/Str)})

(def SSLOptions
  (schema/either
    {:ssl-cert    schema/Str
     :ssl-ca-cert schema/Str
     :ssl-key     schema/Str}
    {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helper Functions

(schema/defn ^:always-validate extract-ssl-opts :- SSLOptions
  [config]
  (let [ssl-opts (select-keys config [:ssl-cert :ssl-key :ssl-ca-cert])
        ssl-configured? (= 3 (count (keys ssl-opts)))
        incomplete-ssl? (and (not ssl-configured?) (< 0 (count (keys ssl-opts))))]
    (cond
      ssl-configured? ssl-opts
      incomplete-ssl? (do (log/warn (str "Not configuring SSL, as only some SSL options were set. "
                                         "To configure SSL, the ssl-cert, ssl-key, and ssl-ca-cert "
                                         "must all be set in the file sync client configuration."))
                          {})
      :else {})))