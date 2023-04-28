(ns puppetlabs.puppetserver.common
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]
            [slingshot.slingshot :as sling])
  (:import (java.util.concurrent TimeUnit)))

(def Environment
  "Schema for environment names. Alphanumeric and _ only."
  (schema/pred (comp not nil? (partial re-matches #"\w+")) "environment"))

(def CodeId
  "Validates that code-id contains only alpha-numerics and
  '-', '_', ';', or ':'."
  (schema/pred (comp not (partial re-find #"[^_\-:;a-zA-Z0-9]")) "code-id"))

(schema/defn environment-validation-error-msg
  [environment :- schema/Str]
  (i18n/tru "The environment must be purely alphanumeric, not ''{0}''"
            environment))

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
