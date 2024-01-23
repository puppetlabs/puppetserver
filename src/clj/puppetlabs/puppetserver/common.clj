(ns puppetlabs.puppetserver.common
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [schema.core :as schema]
            [slingshot.slingshot :as sling])
  (:import (java.util List Map Set)
           (java.util.concurrent TimeUnit)
           (org.yaml.snakeyaml Yaml)))

(def Environment
  "Schema for environment names. Alphanumeric and _ only."
  (schema/pred (comp not nil? (partial re-matches #"\w+")) "environment"))

(def CodeId
  "Validates that code-id contains only alpha-numerics and
  '-', '_', ';', or ':'."
  (schema/pred (comp not (partial re-find #"[^_\-:;a-zA-Z0-9]")) "code-id"))

(defn positive-integer?
  [i]
  (and (integer? i)
       (pos? i)))

(def PosInt
  "Any integer z in Z where z > 0."
  (schema/pred positive-integer? 'positive-integer?))

(schema/defn environment-validation-error-msg
  [environment :- schema/Str]
  (i18n/tru "The environment must be purely alphanumeric, not ''{0}''"
            environment))

;; Pattern is one or more digit followed by time unit
(def digits-with-unit-pattern #"(\d+)(y|d|h|m|s)")
(def repeated-digits-with-unit-pattern #"((\d+)(y|d|h|m|s))+")

(defn duration-string?
  "Returns true if string is formatted with duration string pairs only, otherwise returns nil.
   Ignores whitespace."
  [maybe-duration-string]
  (when (string? maybe-duration-string)
    (let [no-whitespace-string (clojure.string/replace maybe-duration-string #" " "")]
      (some? (re-matches repeated-digits-with-unit-pattern no-whitespace-string)))))

(defn duration-str->sec
  "Converts a string containing any combination of duration string pairs in the format '<num>y' '<num>d' '<num>m' '<num>h' '<num>s'
   to a total number of seconds.
   nil is returned if the input is not a string or not a string containing any valid duration string pairs."
  [string-input]
  (when (duration-string? string-input)
    (let [pattern-matcher (re-matcher digits-with-unit-pattern string-input)
          first-match (re-find pattern-matcher)]
      (loop [[_match-str digits unit] first-match
             running-total 0]
        (let [unit-in-seconds (case unit
                                "y" 31536000 ;; 365 day year, not a real year
                                "d" 86400
                                "h" 3600
                                "m" 60
                                "s" 1)
              total-seconds (+ running-total (* (Integer/parseInt digits) unit-in-seconds))
              next-match (re-find pattern-matcher)]
          (if (some? next-match)
            (recur next-match total-seconds)
            total-seconds))))))

(schema/defn code-id-validation-error-msg
  [code-id :- schema/Str]
  (i18n/tru "Invalid code-id ''{0}''. Must contain only alpha-numerics and ''-'', ''_'', '';'', or '':''"
            code-id))

(defmacro with-safe-read-lock
  "Given a ReentrantReadWriteLock, acquire the read lock, and hold it for the length of the execution of the body.
  If the lock can't be acquired, throw an exception to indicate a timeout.  Log behaviors at trace level to aid with
  supportability"
  [read-write-lock descriptor timeout-in-seconds & body]
  `(let [l# (.readLock ~read-write-lock)
         descriptor# ~descriptor
         timeout# ~timeout-in-seconds]
     (log/trace (i18n/trs "Attempt to acquire read lock \"{0}\"" descriptor#))
     (if (.tryLock l# timeout# TimeUnit/SECONDS)
       (try
         (log/trace (i18n/trs "Acquired read lock \"{0}\"" descriptor#))
         (do
           ~@body)
         (finally
           (.unlock l#)
           (log/trace (i18n/trs "Released read lock \"{0}\"" descriptor#))))
       (do
         (log/info (i18n/trs "Read Lock acquisition timed out \"{0}\"" descriptor#))
         (sling/throw+
           {:kind :lock-acquisition-timeout
            :msg (i18n/tru "Failed to acquire read lock \"{0}\" within {1} seconds" descriptor# timeout#)})))))

(defmacro with-safe-write-lock
  "Given a ReentrantReadWriteLock, acquire the write lock, and hold it for the length of the execution of the body.
  If the lock can't be acquired, throw an exception to indicate a timeout.  Log behaviors at trace level to aid with
  supportability"
  [read-write-lock descriptor timeout-in-seconds & body]
  `(let [l# (.writeLock ~read-write-lock)
         descriptor# ~descriptor
         timeout# ~timeout-in-seconds]
     (log/trace (i18n/trs "Attempt to acquire write lock \"{0}\"" descriptor#))
     (if (.tryLock l# timeout# TimeUnit/SECONDS)
       (try
         (log/trace (i18n/trs "Acquired write lock \"{0}\"" descriptor#))
         (do
           ~@body)
         (finally
           (.unlock l#)
           (log/trace (i18n/trs "Released write lock \"{0}\"" descriptor#))))
       (do
         (log/info (i18n/trs "Write Lock acquisition timed out \"{0}\"" descriptor#))
         (sling/throw+
           {:kind :lock-acquisition-timeout
            :msg (i18n/tru "Failed to acquire write lock \"{0}\" within {1} seconds" descriptor# timeout#)})))))

(defmacro with-safe-lock
  "Given a ReentrantLock, acquire the lock, and hold it for the length of the execution of the body.
  If the lock can't be acquired, throw an exception to indicate a timeout.  Log behaviors at trace level to aid with
  supportability"
  [reentrant-lock descriptor timeout-in-seconds & body]
  `(let [l# ~reentrant-lock
         descriptor# ~descriptor
         timeout# ~timeout-in-seconds]
     (log/trace (i18n/trs "Attempt to acquire lock \"{0}\"" descriptor#))
     (if (.tryLock l# timeout# TimeUnit/SECONDS)
       (try
         (log/trace (i18n/trs "Acquired lock \"{0}\"" descriptor#))
         (do
           ~@body)
         (finally
           (.unlock l#)
           (log/trace (i18n/trs "Released lock \"{0}\"" descriptor#))))
       (do
         (log/info (i18n/trs "Lock acquisition timed out \"{0}\"" descriptor#))
         (sling/throw+
           {:kind :lock-acquisition-timeout
            :msg (i18n/tru "Failed to acquire lock \"{0}\" within {1} seconds" descriptor# timeout#)})))))

(defprotocol JavaMap->ClojureMap
  (java->clj [o]))

(extend-protocol JavaMap->ClojureMap
  Map
  (java->clj [o] (let [entries (.entrySet o)]
                   (reduce (fn [m [^String k v]]
                             (assoc m (keyword k) (java->clj v)))
                           {} entries)))

  List
  (java->clj [o] (vec (map java->clj o)))

  Set
  (java->clj [o] (set (map java->clj o)))

  Object
  (java->clj [o] o)

  nil
  (java->clj [_] nil))

(defn parse-yaml
  [yaml-string]
  ;; default in snakeyaml 2.0 is to not allow
  ;; global tags, which is the source of exploits.
  (let [yaml (new Yaml)
        data (.load yaml ^String yaml-string)]
    (java->clj data)))

(defn extract-file-names-from-paths
  "Given a sequence of java.nio.file.Path objects, return a lazy sequence of the file names of the file represented
  by those paths. Example ['/foo/bar/baz.tmp'] will result in ['baz.tmp']"
  [paths-to-files]
  (map #(.toString (.getFileName %)) paths-to-files))

(defn remove-suffix-from-file-names
  "Given a suffix, and a sequence of file-names, remove the suffix from the filenames"
  [files suffix]
  (let [suffix-size (count suffix)]
    (map (fn [s]
           (if (str/ends-with? s suffix)
             (subs s 0 (- (count s) suffix-size))
             s))
         files)))
