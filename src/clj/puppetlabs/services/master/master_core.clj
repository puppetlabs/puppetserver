(ns puppetlabs.services.master.master-core
  (:import (java.io FileInputStream)
           (clojure.lang IFn)
           (java.util List Map Map$Entry)
           (org.jruby RubySymbol)
           (org.jruby.exceptions RaiseException)
           (com.codahale.metrics MetricRegistry Gauge)
           (java.lang.management ManagementFactory))
  (:require [me.raynes.fs :as fs]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.puppetserver.common :as ps-common]
            [puppetlabs.comidi :as comidi]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.puppetserver.jruby-request :as jruby-request]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.core :as middleware]
            [puppetlabs.ring-middleware.utils :as middleware-utils]
            [cheshire.core :as cheshire]
            [clojure.string :as str]
            [bidi.schema :as bidi-schema]
            [ring.middleware.params :as ring]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.metrics :as metrics]
            [puppetlabs.metrics.http :as http-metrics]
            [puppetlabs.http.client.metrics :as http-client-metrics]
            [puppetlabs.http.client.common :as http-client-common]
            [puppetlabs.trapperkeeper.services.status.status-core :as status-core]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def puppet-API-version
  "v3")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(def EnvironmentClassesFileClassParameter
  "Schema for an individual parameter found in a class defined within an
  environment_classes file.  Includes the name of the parameter.  Optionally,
  if available in the parameter definition, includes the type, source text for
  the parameter's default value, and a literal (primitive data type)
  representation of the parameter's default value.

  For example, if a class parameter were defined like this in a Puppet
  manifest ...

    Integer $someint = 3

  ... then the map representation of that parameter would be:

  {:name \"someint\",
   :type \"Integer\",
   :default_source \"3\",
   :default_literal 3
  }

  For a parameter default value which contains an expression - something that
  cannot be coerced into a primitive data type - the map representation omits
  the default_literal.  For example, this parameter definition in a Puppet
  manifest ...

   String $osfam = \"$::osfamily\"

  ... would produce a map representation which looks like this ...

  {:name \"osfam\",
   :type \"String\",
   :default_source \"\"$::osfamily\"\",
  }

  The data types that could be used in the value for a :default_literal may
  vary over time, as the Puppet language continues to evolve.  For this
  reason, the more permissive schema/Any is used here.  General data types
  that we could see based on current types that the Puppet language allows
  today are in the table below:

  Puppet          | :default_literal value
  ================================================
  String          | java.lang.String
  Boolean         | java.lang.Boolean
  Float/Numeric   | java.lang.Double
  Integer/Numeric | java.lang.Long
  Array           | clojure.lang.LazySeq
  Hash            | clojure.lang.PersistentTreeMap

  For the Regexp, Undef, Default, any Hash objects (top-level or nested within
  another Array or Hash) whose keys are not of type String, and any Array
  or Hash objects (top-level or nested within another Array or Hash) which
  contain a Regexp, Undef, or Default typed value, the :default_literal value
  will not be populated.  These are not populated because they cannot be
  represented in JSON transfer without some loss of fidelity as compared to what
  the original data type from the manifest was."
  {:name schema/Str
   (schema/optional-key :type) schema/Str
   (schema/optional-key :default_source) schema/Str
   (schema/optional-key :default_literal) schema/Any})

(def EnvironmentClassesFileClass
  "Schema for an individual class found within an environment_classes file
  entry.  Includes the name of the class and a vector of information about
  each parameter found in the class definition."
  {:name schema/Str
   :params [EnvironmentClassesFileClassParameter]})

(def EnvironmentClassesFileWithError
  "Schema for an environment_classes file that could not be parsed.  Includes
   the path to the file and an error string containing details about the
   problem encountered during parsing."
  {:path schema/Str
   :error schema/Str})

(def EnvironmentClassesFileWithClasses
  "Schema for an environment_classes file that was successfully parsed.
  Includes the path to the file and a vector of information about each class
  found in the file."
  {:path schema/Str
   :classes [EnvironmentClassesFileClass]})

(def EnvironmentClassesFileEntry
  "Schema for an individual file entry which is part of the return payload
  for an environment_classes request."
  (schema/conditional
   #(contains? % :error) EnvironmentClassesFileWithError
   #(contains? % :classes) EnvironmentClassesFileWithClasses))

(def EnvironmentClassesInfo
  "Schema for the return payload an environment_classes request."
  {:name schema/Str
   :files [EnvironmentClassesFileEntry]})

(def EnvironmentModuleInfo
  "Schema for a given module that can be returned within the
  :modules key in EnvironmentModulesInfo."
  {:name schema/Str
   :version (schema/maybe schema/Str)})

(def EnvironmentModulesInfo
  "Schema for the return payload for an environment_classes request."
  {:name schema/Str
   :modules [EnvironmentModuleInfo]})

(def TaskData
  "Response from puppet's TaskInformationService for data on a single
  task, *after* it has been converted to a Clojure map."
  {:metadata (schema/maybe {schema/Keyword schema/Any})
   :files [{:name schema/Str :path schema/Str}]
   (schema/optional-key :error) {:msg schema/Str
                                 :kind schema/Str
                                 :details {schema/Keyword schema/Any}}})

(def TaskDetails
  "A filled-in map of information about a task."
  {:metadata {schema/Keyword schema/Any}
   :name schema/Str
   :files [{:filename schema/Str
            :sha256 schema/Str
            :size_bytes schema/Int
            :uri {:path schema/Str
                  :params {:environment schema/Str
                           (schema/optional-key :code_id) schema/Str}}}]})

(defn obj-or-ruby-symbol-as-string
  "If the supplied object is of type RubySymbol, returns a string
  representation of the RubySymbol.  Otherwise, just returns the original
  object."
  [obj]
  (if (instance? RubySymbol obj)
    (.asJavaString obj)
    obj))

(defn obj->keyword
  "Attempt to convert the supplied object to a Clojure keyword.  On failure
  to do so, throws an IllegalArgumentException."
  [obj]
  (if-let [obj-as-keyword (-> obj obj-or-ruby-symbol-as-string keyword)]
    obj-as-keyword
    (throw (IllegalArgumentException.
            (i18n/trs "Object cannot be coerced to a keyword: {0}" obj)))))

(defn sort-nested-info-maps
  "For a data structure, recursively sort any nested maps and sets descending
  into map values, lists, vectors and set members as well. The result should be
  that all maps in the data structure become explicitly sorted with natural
  ordering. This can be used before serialization to ensure predictable
  serialization.

  The returned data structure is not a transient so it is still able to be
  modified, therefore caution should be taken to avoid modification else the
  data will lose its sorted status.

  This function was copypasta'd from clj-kitchensink's core/sort-nested-maps.
  sort-nested-maps can only deep sort a structure that contains native Clojure
  types, whereas this function includes a couple of changes which handle the
  sorting of the data structure returned from JRuby for a call to get
  environment class info:

  1) This function sorts keys within any `java.util.Map`, as opposed to just an
     object for which `map?` returns true.

  2) This function attempts to coerces any keys found within a map to a
     Clojure keyword before using `sorted-map` to sort the keys.
     `sorted-map` would otherwise throw an error upon encountering a
     non-keyword type key."
  [data]
  (cond
    (instance? Map data)
    (into (sorted-map) (for [[k v] data]
                         [(obj->keyword k)
                          (sort-nested-info-maps v)]))

    (instance? Iterable data)
    (map sort-nested-info-maps data)

    :else data))

(schema/defn ^:always-validate
  if-none-match-from-request :- (schema/maybe String)
  "Retrieve the value of an 'If-None-Match' HTTP header from the supplied Ring
  request.  If the header is not found, returns nil."
  [request :- {schema/Keyword schema/Any}]
  ;; SERVER-1153 - For a non-nil value, the characters '--gzip' will be
  ;; stripped from the end of the value which is returned.  The '--gzip'
  ;; characters are added by the Jetty web server to an HTTP Etag response
  ;; header for cases in which the corresponding response payload has been
  ;; gzipped.  Newer versions of Jetty, 9.3.x series, have logic in the
  ;; GzipHandler to strip these characters back off of the If-None-Match header
  ;; value before the Ring request would see it.  The version of Jetty being
  ;; used at the time this code was written (9.2.10), however, did not have this
  ;; logic to strip the "--gzip" characters from the incoming header.  This
  ;; function compensates for that by stripping the characters here - before
  ;; other Puppet Server code would use it. When/if Puppet Server is upgraded to
  ;; a version of trapperkeeper-webserver-jetty9 which is based on Jetty 9.3.x
  ;; or newer, it may be safe to take out the line that removes the '--gzip'
  ;; characters.
  (some-> (rr/get-header request "If-None-Match")
          (str/replace #"--gzip$" "")))

(schema/defn ^:always-validate
  add-path-to-file-entry :- Map
  "Convert the value for a manifest file entry into an appropriate map for
  use in serializing an environment_classes response to JSON."
  [file-detail :- Map
   file-name :- schema/Str]
  (.put file-detail "path" file-name)
  file-detail)

(schema/defn ^:always-validate
  manifest-info-from-jruby->manifest-info-for-json
    :- EnvironmentClassesFileEntry
  "Convert the per-manifest file information received from the jruby service
  into an appropriate map for use in serializing an environment_classes
  response to JSON."
  [file-info :- Map$Entry]
  (-> file-info
      val
      (add-path-to-file-entry (key file-info))
      sort-nested-info-maps))

(schema/defn ^:always-validate
  class-info-from-jruby->class-info-for-json :- EnvironmentClassesInfo
  "Convert a class info map received from the jruby service into an
  appropriate map for use in serializing an environment_classes response to
  JSON.  The map that this function returns should be 'sorted' by key - both
  at the top-level and within any nested map - so that it will consistently
  serialize to the exact same content.  For this reason, this function and
  the functions that this function calls use the `sorted-map` and
  `sort-nested-java-maps` helper functions when constructing / translating
  maps."
  [info-from-jruby :- Map
   environment :- schema/Str]
  (->> info-from-jruby
       (map manifest-info-from-jruby->manifest-info-for-json)
       (sort-by :path)
       (vec)
       (sorted-map :name environment :files)))

(schema/defn ^:always-validate
  response-with-etag :- ringutils/RingResponse
  "Create a Ring response, including the supplied 'body' and an HTTP 'Etag'
  header set to the supplied 'etag' parameter."
  [body :- schema/Str
   etag :- schema/Str]
  (-> body
      (rr/response)
      (rr/header "Etag" etag)))

(schema/defn ^:always-validate
  not-modified-response :- ringutils/RingResponse
  "Create an HTTP 304 (Not Modified) response, including an HTTP 'Etag'
  header set to the supplied 'etag' parameter."
  [etag]
  (-> ""
      (response-with-etag etag)
      (rr/status 304)))

(schema/defn ^:always-validate
  environment-class-response! :- ringutils/RingResponse
  "Process the environment class info, returning a Ring response to be
  propagated back up to the caller of the environment_classes endpoint.

  If the specified `environment-class-cache-enabled` is 'true', a SHA-1 hash
  of the class info will be generated.  If the hash is equal to the supplied
  `request-tag`, the response will have an HTTP 304 (Not Modified) status code
  and the response body will be empty.  If the hash is not equal to the supplied
  `request-tag`, the response will have an HTTP 200 (OK) status code and
  the class info, serialized to JSON, will appear in the response body.  The
  newly generated hash code, along with the specified `cache-generation-id`,
  will be passed to the `jruby-service`, to be stored in its environment class
  cache, and will also be returned in the response as the value for an HTTP
  Etag header.

  If the specified `environment-class-cache-enabled` is 'false', no hash
  will be generated for the class info.  The response will always have an
  HTTP 200 (OK) status code and the class info, serialized to JSON, as the
  response body.  An HTTP Etag header will not appear in the response."
  [info-from-jruby :- Map
   environment :- schema/Str
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   request-tag :- (schema/maybe String)
   cache-generation-id :- (schema/maybe schema/Int)
   environment-class-cache-enabled :- schema/Bool]
  (let [info-for-json (class-info-from-jruby->class-info-for-json
                       info-from-jruby
                       environment)]
    (if environment-class-cache-enabled
      (let [info-as-json (cheshire/generate-string info-for-json)
            parsed-tag (ks/utf8-string->sha1 info-as-json)]
        (jruby-protocol/set-environment-class-info-tag!
         jruby-service
         environment
         parsed-tag
         cache-generation-id)
        (if (= parsed-tag request-tag)
          (not-modified-response parsed-tag)
          (-> (response-with-etag info-as-json parsed-tag)
              (rr/content-type "application/json"))))
      (middleware-utils/json-response 200 info-for-json))))

(schema/defn ^:always-validate
  all-tasks-response! :- ringutils/RingResponse
  "Process the info, returning a Ring response to be propagated back up to the
  caller of the endpoint.
  Returns environment as a list of objects for an eventual future in which
  tasks for all environments can be requested, and a given task will list all
  the environments it is found in."
  [info-from-jruby :- [{schema/Any schema/Any}]
   environment :- schema/Str
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (let [format-task (fn [task-object]
                      {:name (:name task-object)
                       :environment [{:name environment
                                      :code_id nil}]})]
    (->> (map format-task info-from-jruby)
         (middleware-utils/json-response 200))))

(defn describe-task-file
  [get-code-content env-name code-id file-data]
  (let [file (:path file-data)
        subpath (:name file-data)
        basename (fs/base-name subpath)
        size (fs/size file)
        sha256 (ks/file->sha256 (io/file file))
        ;; we trust the file path from Puppet, so extract the subpath from file
        static-path (re-find (re-pattern (str #"[^/]+/[a-z][a-z0-9_]*/(?:tasks|files|lib)/.*" basename)) file)
        [_ module-name mount rest] (str/split static-path #"/" 4)
        uri (try
              ;; if code content can be retrieved, then use static_file_content
              (when (not= sha256 (ks/stream->sha256 (get-code-content env-name code-id static-path)))
                (throw (Exception. (i18n/trs "file did not match versioned file contents"))))
              {:path (str "/puppet/v3/static_file_content/" static-path)
               :params {:environment env-name :code_id code-id}}
              (catch Exception e
                (log/debug (i18n/trs "Static file unavailable for {0}: {1}" file e))
                {:path (case mount
                         "files" (format "/puppet/v3/file_content/modules/%s/%s" module-name rest)
                         "tasks" (format "/puppet/v3/file_content/tasks/%s/%s" module-name rest)
                         "lib" (format "/puppet/v3/file_content/plugins/%s" rest))
                 :params {:environment env-name}}))]
    {:filename subpath
     :sha256 sha256
     :size_bytes size
     :uri uri}))

(defn full-task-name
  "Construct a full task name from the two components. If the task's short name
  is 'init', then the second component is omitted so the task name is just the
  module's name."
  [module-name task-shortname]
  (if (= task-shortname "init")
    module-name
    (str module-name "::" task-shortname)))

(schema/defn ^:always-validate
  task-data->task-details :- TaskDetails
  "Fills in a bare TaskData map by examining the files it refers to,
  returning TaskDetails."
  [task-data :- TaskData
   get-code-content :- IFn
   env-name :- schema/Str
   code-id :- (schema/maybe schema/Str)
   module-name :- schema/Str
   task-name :- schema/Str]
  (if (:error task-data)
    (throw+ (:error task-data))
    {:metadata (or (:metadata task-data) {})
     :name (full-task-name module-name task-name)
     :files (mapv (partial describe-task-file get-code-content env-name code-id)
                  (:files task-data))}))

(schema/defn environment-not-found :- ringutils/RingResponse
  "Ring handler to provide a standard error when an environment is not found."
  [environment :- schema/Str]
  (rr/not-found (i18n/tru "Could not find environment ''{0}''" environment)))

(schema/defn module-not-found :- ringutils/RingResponse
  "Ring handler to provide a standard error when a module is not found."
  [module :- schema/Str]
  (rr/not-found (i18n/tru "Could not find module ''{0}''" module)))

(schema/defn task-not-found :- ringutils/RingResponse
  "Ring handler to provide a standard error when a task is not found."
  [task :- schema/Str]
  (rr/not-found (i18n/tru "Could not find task ''{0}''" task)))

(schema/defn ^:always-validate
  environment-class-info-fn :- IFn
  "Middleware function for constructing a Ring response from an incoming
  request for environment_classes information."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   environment-class-cache-enabled :- schema/Bool]
  (fn [request]
    (let [environment (jruby-request/get-environment-from-request request)
          cache-generation-id
          (jruby-protocol/get-environment-class-info-cache-generation-id!
           jruby-service
           environment)]
      (if-let [class-info
               (jruby-protocol/get-environment-class-info jruby-service
                                                          (:jruby-instance
                                                           request)
                                                          environment)]
        (environment-class-response! class-info
                                     environment
                                     jruby-service
                                     (if-none-match-from-request request)
                                     cache-generation-id
                                     environment-class-cache-enabled)
        (environment-not-found environment)))))

(schema/defn ^:always-validate
  module-info-from-jruby->module-info-for-json  :- EnvironmentModulesInfo
  "Creates a new map with a top level key `name` that corresponds to the
  requested environment and a top level key `modules` which contains the module
  information obtained from JRuby."
  [info-from-jruby :- List
   environment :- schema/Str]
  (->> info-from-jruby
       sort-nested-info-maps
       vec
       (sorted-map :name environment :modules)))

(schema/defn ^:always-validate
  environment-module-response! :- ringutils/RingResponse
  "Process the environment module information, returning a ring response to be
  propagated back up to the caller of the environment_modules endpoint."
  ([info-from-jruby :- Map]
   (let [all-info-as-json (map #(module-info-from-jruby->module-info-for-json
                                  (val %)
                                  (name (key %)))
                               (sort-nested-info-maps info-from-jruby))]
     (middleware-utils/json-response 200 all-info-as-json)))
  ([info-from-jruby :- List
    environment :- schema/Str]
   (let [info-as-json (module-info-from-jruby->module-info-for-json
                        info-from-jruby environment)]
     (middleware-utils/json-response 200 info-as-json))))

(schema/defn ^:always-validate
  environment-module-info-fn :- IFn
  "Middleware function for constructing a Ring response from an incoming
  request for environment_modules information."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (fn [request]
    (if-let [environment (jruby-request/get-environment-from-request request)]
      (if-let [module-info
               (jruby-protocol/get-environment-module-info jruby-service
                                                           (:jruby-instance request)
                                                           environment)]
        (environment-module-response! module-info
                                      environment)
        (rr/not-found (i18n/tru "Could not find environment ''{0}''" environment)))
      (let [module-info
            (jruby-protocol/get-all-environment-module-info jruby-service
                                                            (:jruby-instance request))]
        (environment-module-response! module-info)))))

(schema/defn ^:always-validate
  all-tasks-fn :- IFn
  "Middleware function for constructing a Ring response from an incoming
  request for tasks information."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (fn [request]
    (let [environment (jruby-request/get-environment-from-request request)]
      (if-let [task-info-for-env
               (sort-nested-info-maps
                 (jruby-protocol/get-tasks jruby-service
                                           (:jruby-instance
                                             request)
                                           environment))]
        (all-tasks-response! task-info-for-env
                             environment
                             jruby-service)
        (environment-not-found environment)))))

(schema/defn ^:always-validate
  task-details :- TaskDetails
  "Returns a TaskDetails map for the task matching the given environment,
  module, and name.

  Will throw a JRuby RaiseException with (EnvironmentNotFound),
  (MissingModule), or (TaskNotFound) if any of those conditions occur."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   jruby-instance
   code-content-fn :- IFn
   environment-name :- schema/Str
   code-id :- (schema/maybe schema/Str)
   module-name :- schema/Str
   task-name :- schema/Str]
  (-> (jruby-protocol/get-task-data jruby-service
                                    jruby-instance
                                    environment-name
                                    module-name
                                    task-name)
      sort-nested-info-maps
      (task-data->task-details code-content-fn environment-name code-id module-name task-name)))

(schema/defn exception-matches? :- schema/Bool
  [^Exception e :- Exception
   pattern :- schema/Regex]
  (->> e
       .getMessage
       (re-find pattern)
       boolean))

(defn handle-task-details-jruby-exception
  "Given a JRuby RaiseException arising from a call to task-details, constructs
  a 4xx error response if appropriate, otherwise re-throws."
  [jruby-exception environment module task]
  (cond
    (exception-matches? jruby-exception #"^\(EnvironmentNotFound\)")
    (environment-not-found environment)

    (exception-matches? jruby-exception #"^\(MissingModule\)")
    (module-not-found module)

    (exception-matches? jruby-exception #"^\(TaskNotFound\)")
    (task-not-found task)

    :else
    (throw jruby-exception)))

(defn is-task-error?
  [err]
  (and
   (map? err)
   (:kind err)
   (str/starts-with? (:kind err) "puppet.task")))

(schema/defn ^:always-validate
  task-details-fn :- IFn
  "Middleware function for constructing a Ring response from an incoming
  request for detailed task information."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   current-code-id-fn :- IFn]
  (fn [request]
    (let [environment (jruby-request/get-environment-from-request request)
          module (get-in request [:route-params :module-name])
          task (get-in request [:route-params :task-name])]
      (try+ (->> (task-details jruby-service
                               (:jruby-instance request)
                               get-code-content-fn
                               environment
                               (current-code-id-fn environment)
                               module
                               task)
                 (middleware-utils/json-response 200))
            (catch is-task-error?
              {:keys [kind msg details]}
              (middleware-utils/json-response 500
                                              {:kind kind
                                               :msg msg
                                               :details details}))
            (catch RaiseException e
              (handle-task-details-jruby-exception e environment module task))))))

(schema/defn ^:always-validate
  wrap-with-etag-check :- IFn
  "Middleware function which validates whether or not the If-None-Match
  header on an incoming environment_classes request matches the last Etag
  computed for the environment whose info is being requested.  If the two
  match, the middleware function returns an HTTP 304 (Not Modified) Ring
  response.  If the two do not match, the request is threaded through to the
  supplied 'f' function."
  [handler :- IFn
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (fn [request]
    (let [environment (jruby-request/get-environment-from-request request)
          request-tag (if-none-match-from-request request)]
      (if (and request-tag
               (= request-tag
                  (jruby-protocol/get-environment-class-info-tag
                   jruby-service
                   environment)))
        (not-modified-response request-tag)
        (handler request)))))

(schema/defn ^:always-validate
  environment-class-handler :- IFn
  "Handler for processing an incoming environment_classes Ring request"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   environment-class-cache-enabled :- schema/Bool]
  (->
   (environment-class-info-fn jruby-service
                              environment-class-cache-enabled)
   (jruby-request/wrap-with-jruby-instance jruby-service)
   (wrap-with-etag-check jruby-service)
   jruby-request/wrap-with-environment-validation
   jruby-request/wrap-with-error-handling))


(schema/defn ^:always-validate
  environment-module-handler :- IFn
  "Handler for processing an incoming environment_modules Ring request"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (->
   (environment-module-info-fn jruby-service)
   (jruby-request/wrap-with-jruby-instance jruby-service)
   (jruby-request/wrap-with-environment-validation true)
   jruby-request/wrap-with-error-handling))

(schema/defn ^:always-validate
  all-tasks-handler :- IFn
  "Handler for processing an incoming all_tasks Ring request"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)]
  (-> (all-tasks-fn jruby-service)
      (jruby-request/wrap-with-jruby-instance jruby-service)
      jruby-request/wrap-with-environment-validation
      jruby-request/wrap-with-error-handling))

(schema/defn ^:always-validate
  task-details-handler :- IFn
  "Handler for processing an incoming /tasks/:module/:task-name Ring request"
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   current-code-id-fn :- IFn]
  (-> (task-details-fn jruby-service get-code-content-fn current-code-id-fn)
      (jruby-request/wrap-with-jruby-instance jruby-service)
      jruby-request/wrap-with-environment-validation
      jruby-request/wrap-with-error-handling))

(schema/defn ^:always-validate valid-static-file-path?
  "Helper function to decide if a static_file_content path is valid.
  The access here is designed to mimic Puppet's file_content endpoint."
  [path :- schema/Str]
  ;; Here, keywords represent a single element in the path. Anything between two '/' counts.
  ;; The second vector takes anything else that might be on the end of the path.
  ;; Below, this corresponds to '*/*/files/**' or '*/*/tasks/**' in a filesystem glob.
  (bidi.bidi/match-route [[#"[^/]+/" :module-name #"/(files|tasks|lib)/" [#".+" :rest]] :_]
                         path))

(defn static-file-content-request-handler
  "Returns a function which is the main request handler for the
  /static_file_content endpoint, utilizing the provided implementation of
  `get-code-content`"
  [get-code-content]
  (fn [req]
    (let [environment (get-in req [:params "environment"])
          code-id (get-in req [:params "code_id"])
          file-path (get-in req [:params :rest])]
      (cond
        (some empty? [environment code-id file-path])
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body (i18n/tru "Error: A /static_file_content request requires an environment, a code-id, and a file-path")}

        (not (nil? (schema/check ps-common/Environment environment)))
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body (ps-common/environment-validation-error-msg environment)}

        (not (nil? (schema/check ps-common/CodeId code-id)))
        {:status 400
         :headers {"Content-Type" "text/plain"}
         :body (ps-common/code-id-validation-error-msg code-id)}

        (not (valid-static-file-path? file-path))
        {:status 403
         :headers {"Content-Type" "text/plain"}
         :body (i18n/tru "Request Denied: A /static_file_content request must be a file within the files, lib, or tasks directory of a module.")}

        :else
        {:status 200
         :headers {"Content-Type" "application/octet-stream"}
         :body (get-code-content environment code-id file-path)}))))

(def MetricIdsForStatus (schema/atom [[schema/Str]]))

(schema/defn http-client-metrics-summary
  :- {:metrics-data [http-client-common/MetricIdMetricData]
      :sorted-metrics-data [http-client-common/MetricIdMetricData]}
  [metric-registry :- MetricRegistry
   metric-ids-to-select :- MetricIdsForStatus]
  (let [metrics-data (map #(http-client-metrics/get-client-metrics-data-by-metric-id metric-registry %)
                          @metric-ids-to-select)
        flattened-data (flatten metrics-data)]
    {:metrics-data flattened-data
     :sorted-metrics-data (sort-by :aggregate > flattened-data)}))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(schema/defn ^:always-validate
  v3-ruby-routes :- bidi-schema/RoutePair
  "v3 route tree for the ruby side of the master service."
  [request-handler :- IFn]
  (comidi/routes
   (comidi/GET ["/node/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_content/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_metadatas/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_metadata/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/file_bucket_file/" [#".*" :rest]] request
               (request-handler request))
   (comidi/PUT ["/file_bucket_file/" [#".*" :rest]] request
               (request-handler request))
   (comidi/HEAD ["/file_bucket_file/" [#".*" :rest]] request
                (request-handler request))

   (comidi/GET ["/catalog/" [#".*" :rest]] request
               (request-handler (assoc request :include-code-id? true)))
   (comidi/POST ["/catalog/" [#".*" :rest]] request
                (request-handler (assoc request :include-code-id? true)))
   (comidi/PUT ["/facts/" [#".*" :rest]] request
               (request-handler request))
   (comidi/PUT ["/report/" [#".*" :rest]] request
               (request-handler request))
   (comidi/GET ["/environment/" [#".*" :environment]] request
               (request-handler (assoc request :include-code-id? true)))
   (comidi/GET "/environments" request
               (request-handler request))
   (comidi/GET ["/status/" [#".*" :rest]] request
               (request-handler request))))

(schema/defn ^:always-validate
  v3-clojure-routes :- bidi-schema/RoutePair
  "v3 route tree for the clojure side of the master service."
  [jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   current-code-id-fn :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (let [environment-class-handler
        (environment-class-handler jruby-service
                                   environment-class-cache-enabled),

        environment-module-handler
        (environment-module-handler jruby-service)

        all-tasks-handler
        (all-tasks-handler jruby-service)

        task-details-handler
        (task-details-handler jruby-service get-code-content-fn current-code-id-fn)

        static-file-content-handler
        (static-file-content-request-handler get-code-content-fn)]
    (comidi/routes
      (comidi/GET ["/environment_classes" [#".*" :rest]] request
                  (environment-class-handler request))
      (comidi/GET ["/environment_modules" [#".*" :rest]] request
                  (environment-module-handler request))
      (comidi/GET ["/tasks/" :module-name "/" :task-name] request
                  (task-details-handler request))
      (comidi/GET ["/tasks"] request
                  (all-tasks-handler request))
      (comidi/GET ["/static_file_content/" [#".*" :rest]] request
                  (static-file-content-handler request)))))

(schema/defn ^:always-validate
  v3-routes :- bidi-schema/RoutePair
  "Creates the routes to handle the master's '/v3' routes, which
   includes '/environments' and the non-CA indirected routes. The CA-related
   endpoints are handled separately by the CA service."
  [ruby-request-handler :- IFn
   clojure-request-wrapper :- IFn
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   current-code-id-fn :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (comidi/context "/v3"
                  (v3-ruby-routes ruby-request-handler)
                  (comidi/wrap-routes
                   (v3-clojure-routes jruby-service
                                      get-code-content-fn
                                      current-code-id-fn
                                      environment-class-cache-enabled)
                   clojure-request-wrapper )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Lifecycle Helper Functions

(defn meminfo-content
  "Read and return the contents of /proc/meminfo, if it exists.  Otherwise
  return nil."
  []
  (when (fs/exists? "/proc/meminfo")
    ; Due to OpenJDK Issue JDK-7132461
    ; (https://bugs.openjdk.java.net/browse/JDK-7132461),
    ; we have to open and slurp a FileInputStream object rather than
    ; slurping the file directly, since directly slurping the file
    ; causes a call to be made to FileInputStream.available().
    (with-open [mem-info-file (FileInputStream. "/proc/meminfo")]
      (slurp mem-info-file))))

; the current max java heap size (-Xmx) in kB defined for with-redefs in tests
(def max-heap-size (/ (.maxMemory (Runtime/getRuntime)) 1024))

(defn validate-memory-requirements!
  "On Linux Distributions, parses the /proc/meminfo file to determine
   the total amount of System RAM, and throws an exception if that
   is less than 1.1 times the maximum heap size of the JVM. This is done
   so that the JVM doesn't fail later due to an Out of Memory error."
  []
  (when-let [meminfo-file-content (meminfo-content)]
    (let [heap-size max-heap-size
          mem-size (Integer. (second (re-find #"MemTotal:\s+(\d+)\s+\S+"
                                               meminfo-file-content)))
          required-mem-size (* heap-size 1.1)]
      (when (< mem-size required-mem-size)
        (throw (Error.
                (format "%s %s %s"
                        (i18n/trs "Not enough available RAM ({0}MB) to safely accommodate the configured JVM heap size of {1}MB." (int (/ mem-size 1024.0)) (int (/ heap-size 1024.0)))
                        (i18n/trs "Puppet Server requires at least {0}MB of available RAM given this heap size, computed as 1.1 * max heap (-Xmx)." (int (/ required-mem-size 1024.0)))
                        (i18n/trs "Either increase available memory or decrease the configured heap size by reducing the -Xms and -Xmx values in JAVA_ARGS in /etc/sysconfig/puppetserver on EL systems or /etc/default/puppetserver on Debian systems."))))))))

(defn register-gauge!
  [registry hostname metric-name metric-fn]
  (.register registry (metrics/host-metric-name hostname metric-name)
             (proxy [Gauge] []
               (getValue []
                 (metric-fn)))))

(schema/defn register-jvm-metrics!
  [registry :- MetricRegistry
   hostname :- schema/Str]
  (register-gauge! registry hostname "uptime"
                   (fn [] (.getUptime (ManagementFactory/getRuntimeMXBean))))
  (let [memory-mbean (ManagementFactory/getMemoryMXBean)
        get-heap-memory (fn [] (.getHeapMemoryUsage memory-mbean))
        get-non-heap-memory (fn [] (.getNonHeapMemoryUsage memory-mbean))]
    (register-gauge! registry hostname "memory.heap.committed"
                     (fn [] (.getCommitted (get-heap-memory))))
    (register-gauge! registry hostname "memory.heap.init"
                     (fn [] (.getInit (get-heap-memory))))
    (register-gauge! registry hostname "memory.heap.max"
                     (fn [] (.getMax (get-heap-memory))))
    (register-gauge! registry hostname "memory.heap.used"
                     (fn [] (.getUsed (get-heap-memory))))

    (register-gauge! registry hostname "memory.non-heap.committed"
                     (fn [] (.getCommitted (get-non-heap-memory))))
    (register-gauge! registry hostname "memory.non-heap.init"
                     (fn [] (.getInit (get-non-heap-memory))))
    (register-gauge! registry hostname "memory.non-heap.max"
                     (fn [] (.getMax (get-non-heap-memory))))
    (register-gauge! registry hostname "memory.non-heap.used"
                     (fn [] (.getUsed (get-non-heap-memory))))

    ;; Unfortunately there isn't an mbean for "total" memory. Dropwizard metrics'
    ;; MetricUsageGaugeSet has "total" metrics that are computed by adding together Heap Memory and
    ;; Non Heap Memory.
    (register-gauge! registry hostname "memory.total.committed"
                     (fn [] (+ (.getCommitted (get-heap-memory))
                               (.getCommitted (get-non-heap-memory)))))
    (register-gauge! registry hostname "memory.total.init"
                     (fn [] (+ (.getInit (get-heap-memory))
                               (.getInit (get-non-heap-memory)))))
    (register-gauge! registry hostname "memory.total.max"
                     (fn [] (+ (.getMax (get-heap-memory))
                               (.getMax (get-non-heap-memory)))))
    (register-gauge! registry hostname "memory.total.used"
                     (fn [] (+ (.getUsed (get-heap-memory))
                               (.getUsed (get-non-heap-memory)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  root-routes :- bidi-schema/RoutePair
  "Creates all of the web routes for the master."
  [ruby-request-handler :- IFn
   clojure-request-wrapper :- IFn
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content-fn :- IFn
   current-code-id-fn :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (comidi/routes
   (v3-routes ruby-request-handler
              clojure-request-wrapper
              jruby-service
              get-code-content-fn
              current-code-id-fn
              environment-class-cache-enabled)))

(schema/defn ^:always-validate
  wrap-middleware :- IFn
  [handler :- IFn
   puppet-version :- schema/Str]
  (-> handler
      (middleware/wrap-uncaught-errors :plain)
      middleware/wrap-request-logging
      i18n/locale-negotiator
      middleware/wrap-response-logging
      (ringutils/wrap-with-puppet-version-header puppet-version)))

(schema/defn ^:always-validate get-master-route-config
  "Get the webserver route configuration for the master service"
  [master-ns :- schema/Keyword
   config :- {schema/Keyword schema/Any}]
  (get-in config [:web-router-service master-ns]))

(schema/defn ^:always-validate
  get-master-mount :- schema/Str
  "Get the webserver mount point that the master service is rooted under"
  [master-ns :- schema/Keyword
   config-route]
  (cond
    ;; if the route config is a map, we need to determine whether it's the
    ;; new-style multi-server config (where there will be a `:route` key and a
    ;; `:server`, key), or the old style where there is a single key that is
    ;; assumed to be our hard-coded route id (`:master-routes`).
    ;; It should be possible to delete this hack (perhaps this entire function)
    ;; when we remove support for legacy routes.
    (and (map? config-route) (or (contains? config-route :route)
                                 (contains? config-route :master-routes)))
    (or (:route config-route)
        (:master-routes config-route))

    (string? config-route)
    config-route

    :else
    (throw (IllegalArgumentException.
             (i18n/trs "Route not found for service {0}" master-ns)))))

(schema/defn ^:always-validate
  get-wrapped-handler :- IFn
  "Conditionally wraps route-handler with authorization-fn before calling
  wrap-middleware on the handler. If use-legacy-auth-conf is not specified,
  defaults to wrapping route-handler with authorization-fn."
  ([route-handler :- IFn
    authorization-fn :- IFn
    puppet-version :- schema/Str]
   (get-wrapped-handler route-handler authorization-fn puppet-version false))
  ([route-handler :- IFn
    authorization-fn :- IFn
    puppet-version :- schema/Str
    use-legacy-auth-conf :- schema/Bool]
   (let [handler-maybe-with-authorization (if use-legacy-auth-conf
                                            route-handler
                                            (authorization-fn route-handler))]
     (wrap-middleware handler-maybe-with-authorization puppet-version))))

(schema/defn ^:always-validate
  construct-root-routes :- bidi-schema/RoutePair
  "Creates a wrapped ruby request handler and a clojure request handler,
  then uses those to create all of the web routes for the master."
  [puppet-version :- schema/Str
   use-legacy-auth-conf :- schema/Bool
   jruby-service :- (schema/protocol jruby-protocol/JRubyPuppetService)
   get-code-content :- IFn
   current-code-id :- IFn
   handle-request :- IFn
   wrap-with-authorization-check :- IFn
   environment-class-cache-enabled :- schema/Bool]
  (let [ruby-request-handler (get-wrapped-handler handle-request
                                                  wrap-with-authorization-check
                                                  puppet-version
                                                  use-legacy-auth-conf)
        clojure-request-wrapper (fn [handler]
                                  (get-wrapped-handler
                                    (ring/wrap-params handler)
                                    wrap-with-authorization-check
                                    puppet-version))]
    (root-routes ruby-request-handler
                 clojure-request-wrapper
                 jruby-service
                 get-code-content
                 current-code-id
                 environment-class-cache-enabled)))

(def MasterStatusV1
  {(schema/optional-key :experimental) {:http-metrics [http-metrics/RouteSummary]
                                        :http-client-metrics [http-client-common/MetricIdMetricData]}})

(def puppet-server-http-client-metrics-for-status
  [["puppet" "report" "http"]
   ["puppetdb" "command" "replace_catalog"]
   ["puppetdb" "command" "replace_facts"]
   ["puppetdb" "command" "store_report"]
   ["puppetdb" "facts" "find"]
   ["puppetdb" "facts" "search"]
   ["puppetdb" "query"]
   ["puppetdb" "resource" "search"]])

(schema/defn ^:always-validate add-metric-ids-to-http-client-metrics-list!
  [metric-id-atom :- MetricIdsForStatus
   metric-ids-to-add :- [[schema/Str]]]
  (swap! metric-id-atom concat metric-ids-to-add))

(schema/defn ^:always-validate v1-status :- status-core/StatusCallbackResponse
  [http-metrics :- http-metrics/HttpMetrics
   http-client-metric-ids-for-status :- MetricIdsForStatus
   metric-registry :- MetricRegistry
   level :- status-core/ServiceStatusDetailLevel]
  (let [level>= (partial status-core/compare-levels >= level)]
    {:state :running
     :status (cond->
              ;; no status info at ':critical' level
              {}
              ;; no extra status at ':info' level yet
              (level>= :info) identity
              (level>= :debug) (-> (assoc-in [:experimental :http-metrics]
                                             (:sorted-routes
                                              (http-metrics/request-summary http-metrics)))
                                   (assoc-in [:experimental :http-client-metrics]
                                             (:sorted-metrics-data
                                              (http-client-metrics-summary
                                               metric-registry
                                               http-client-metric-ids-for-status)))))}))
