(ns puppetlabs.services.file-serving.file-metadata.file-metadata-core
  (:import (org.apache.commons.codec.digest DigestUtils)
           [java.text SimpleDateFormat]
           [java.util Locale Date])
  (:require [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.puppetserver.liberator-utils :as utils]
            [puppetlabs.services.protocols.puppet-fileserver-config :as fileserver-config]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [schema.core :as schema]
            [cheshire.core :as cheshire]
            [compojure.core :as compojure :refer [GET ANY PUT]]
            [liberator.core :refer [defresource]]
            [liberator.representation :as representation]
            [liberator.dev :as liberator-dev]
            [puppetlabs.services.file-serving.config.puppet-fileserver-config-core :as config]
            [puppetlabs.services.file-serving.utils.posix-utils :as posix-utils]
            )
  (:use [clojure.java.io :only (input-stream)]
        [ring.middleware.params]
        ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; 'handler' functions for HTTP endpoints

(defn- checksum-of-file
  [file]
  "Compute the md5 checksum of a given file path"
  (with-open [r (java.nio.file.Files/newInputStream file (into-array java.nio.file.OpenOption []))]
    {
     :type "md5"
     :value (str "{md5}" (DigestUtils/md5Hex r))
     }
  ))

(def date-format (SimpleDateFormat. "yyyy.MM.dd HH:mm:ss Z" Locale/ROOT))

(defn date->ruby
  [time]
  (.format date-format (Date. time)))

(defn ruby->date
  [date]
  (.parse date-format date))

(defn- checksum-of-dir
  [posix-attr]
  "Compute the ctime checksum of a given directory"
    {
     :type "ctime"
     :value (str "{ctime}" (date->ruby (.toMillis (posix-attr "creationTime"))))
     }
    )

(def base-metadata
  {
    :data          {
                     }
    :document_type "FileMetadata"
    :metadata      {
                     :api_version 1
                     }
   })

(defn- get-base-metadata
  [attrs file-path posix-attrs]
  (-> attrs
    (assoc-in [:data :owner] (posix-utils/owner posix-attrs))
    (assoc-in [:data :group] (posix-utils/group posix-attrs))
    (assoc-in [:data :mode] (posix-utils/mode posix-attrs))
    (assoc-in [:data :path] (str (.toAbsolutePath file-path)))))

(defn- get-file-metadata
  [file-path posix-attrs]
  (-> (assoc-in base-metadata [:data :checksum] (checksum-of-file file-path))
      (get-base-metadata file-path posix-attrs)
      (assoc-in [:data :relative_path] nil)
      (assoc-in [:data :destination] nil)
      (assoc-in [:data :type] "file")
      ))

(defn- get-directory-metadata
  [file-path posix-attrs]
  (-> (assoc-in base-metadata [:data :checksum] (checksum-of-dir posix-attrs))
      (get-base-metadata file-path posix-attrs)
      (assoc-in [:data :relative_path] ".")
      (assoc-in [:data :destination] nil)
      (assoc-in [:data :type] "directory")
      ))

(defn- get-link-metadata
  [file-path posix-attrs links]
  (-> (assoc-in base-metadata [:data :checksum] (checksum-of-file file-path))
      (get-base-metadata file-path posix-attrs)
      (assoc-in [:data :relative_path] nil)
      (assoc-in [:data :destination] (.getCanonicalPath (.toFile (posix-utils/resolve-link file-path))))
      (assoc-in [:data :type] "link")
      ))

(def Link schema/Str)
(def SourcePermission schema/Str)

(schema/defn get-metadata
  [mountpath :- config/MountPath
   links :- Link
   source_permissions :- SourcePermission]
  (let [[mount path] mountpath
        file (clojure.java.io/file (:path mount) path)
        file-path (.toPath file)
        posix-attrs (posix-utils/unix-attributes file-path links)]

    (assoc-in (cond
                (.isDirectory file) (get-directory-metadata file-path posix-attrs)
                (java.nio.file.Files/isSymbolicLink file-path) (get-link-metadata file-path posix-attrs links)
                :else (get-file-metadata file-path posix-attrs)) [:data :links] links)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Compojure app

(defn try-to-parse
  [body]
  (try
    (cheshire/parse-stream (io/reader body) true)
    (catch Exception e
      (log/debug e))))

(def media-types
  #{"application/json" "text/pson" "pson"})

(defn content-type-valid?
  [context]
  (let [content-type (get-in context [:request :headers "content-type"])]
    (or
      (nil? content-type)
      (media-types content-type))))

(defn as-json-or-pson
  "This is a stupid hack because of PSON.  We shouldn't have to do this, but
  liberator does not know how to serialize a map as PSON (as it does with JSON),
  so we have to tell it how."
  [x context]
  (let [context-with-media-type (if (string/blank? (get-in context
                                                           [:representation
                                                            :media-type]))
                                  (assoc-in context
                                            [:representation :media-type]
                                            "text/pson")
                                  context)]
    (-> (cheshire/generate-string x)
        (representation/as-response context-with-media-type)
        (assoc :status 200)
        (representation/ring-response))))

(defresource file-metadata
             [path service links source_permissions]
             :allowed-methods [:get]

             :allowed? (fn [context]
                         (->> (fileserver-config/find-mount service path)
                              (second)
                              (fileserver-config/allowed? service (:request context))))

             :available-media-types media-types

             :handle-exception utils/exception-handler

             :exists?
             (fn [context]
               (let [[mount path] (fileserver-config/find-mount service path)
                     file (clojure.java.io/file (:path mount) path)]
                 (.exists file)))

             :handle-ok
             (fn [context]
               (-> (fileserver-config/find-mount service path)
                   (get-metadata links source_permissions)
                   (as-json-or-pson context)))

             :new? false)


(schema/defn routes
  [fileserving-config-service]
  (compojure/context "/:environment" [environment]
                     (compojure/routes
                       (GET "/file_metadata/*" request
                            (let [path (get-in request [:params :*])
                                  links (get-in request [:params :links] "manage")
                                  permissions (get-in request [:params :use_permissions] "use")]
                              (file-metadata path fileserving-config-service links permissions))))))

(schema/defn ^:always-validate
             build-ring-handler
  [fileserving-config-service]
  (-> (routes fileserving-config-service)
      (wrap-params)
      (liberator-dev/wrap-trace :header)           ; very useful for debugging!
      (ringutils/wrap-response-logging)
      (ringutils/wrap-request-logging)))
