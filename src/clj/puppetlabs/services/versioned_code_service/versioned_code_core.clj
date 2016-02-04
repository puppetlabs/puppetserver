(ns puppetlabs.services.versioned-code-service.versioned-code-core
  (:require [schema.core :as schema]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetserver.shell-utils :as shell-utils]
            [clojure.string :as string])
  (:import (java.io IOException InputStream)
           (org.apache.commons.io IOUtils)))

(schema/defn ^:always-validate
  success-with-stderr-msg :- schema/Str
  [cmd :- schema/Str
   stderr :- schema/Str]
  (format "Error output generated while running '%s'. stderr: '%s'"
          cmd stderr))

(schema/defn ^:always-validate
  nonzero-msg :- schema/Str
  [cmd :- schema/Str
   exit-code :- schema/Int
   stdout :- schema/Str
   stderr :- schema/Str]
  (format (str "Non-zero exit code returned while running '%s'. "
               "exit-code: '%d', stdout: '%s', stderr: '%s'")
          cmd exit-code stdout stderr))

(schema/defn ^:always-validate
  execution-error-msg :- schema/Str
  [cmd :- schema/Str
   e :- Exception]
  (format (str "Running script generated an error. "
               "Command executed: '%s', error generated: '%s'")
              cmd (.getMessage e)))

(schema/defn ^:always-validate execute-code-id-script! :- (schema/maybe schema/Str)
  [code-id-script :- schema/Str
   environment :- schema/Str]
  (let [log-execution-error! #(log/error (execution-error-msg code-id-script %))]
    (try
      (let [{:keys [exit-code stderr stdout]} (shell-utils/execute-command
                                               code-id-script
                                               {:args [environment]})]
        ; TODO Decide what to do about normalizing/sanitizing output with respect to
        ; control characters and encodings

        ;; There are three cases we care about here:
        ;; - exit code is 0 and no stderr generated: groovy. return stdout
        ;; - exit code is 0 and stderr was generated: that's fine. log an error
        ;;   about the stderr and return stdout.
        ;; - exit code is non-zero: oh no! log an error with all the details and
        ;;   return nil
        (if (zero? exit-code)
          (do
            (when-not (string/blank? stderr)
              (log/error (success-with-stderr-msg code-id-script stderr)))
            (string/trim-newline stdout))
          (do
            (log/error (nonzero-msg code-id-script exit-code stdout stderr))
            nil)))
      (catch IllegalArgumentException e
        (log-execution-error! e))
      (catch IOException e
        (log-execution-error! e))
      (catch InterruptedException e
        (log-execution-error! e)))))

(schema/defn valid-code-id? :- schema/Bool
  "Returns false if code-id contains anything but alpha-numerics and
  '-', '_', or ':'. nil is a valid code-id"
  [code-id :- (schema/maybe String)]
  (or
    (nil? code-id)
    (not (re-find #"[^_\-:;a-zA-Z0-9]" code-id))))

(schema/defn validation-error-msg :- String
  [code-id :- String]
  (format
    "Invalid code-id '%s'. Must contain only alpha-numerics and '-', '_', or ':'"
    code-id))

(schema/defn get-current-code-id! :- (schema/maybe String)
  "Execute the code-id-script and validate its output before returning"
  [code-id-script :- schema/Str
   environment :- schema/Str]
  (let [code-id (execute-code-id-script! code-id-script environment)]
    (when-not (valid-code-id? code-id)
      (throw (IllegalStateException. (validation-error-msg code-id))))
    code-id))

(schema/defn ^:always-validate
  execute-code-content-script! :- InputStream
  "Given a string path to an executable script and the environment, code-id, and
  file-path of a desired revision of a file, returns that file as a stream. Does
  no exception catching: Being unable to compute code content is considered a
  fatal error and should be handled by calling code appropriately."
  [code-content-script :- schema/Str
   environment :- schema/Str
   code-id :- schema/Str
   file-path :- schema/Str]
  (let [throw-execution-error! (fn [e]
                                 (throw (IllegalStateException.
                                         (execution-error-msg code-content-script e))))]
    (try
      (let [{:keys [stdout stderr exit-code]} (shell-utils/execute-command-streamed
                                               code-content-script
                                               {:args [environment code-id file-path]})]
        (if (zero? exit-code)
          (do
            (when-not (string/blank? stderr)
              (log/error (success-with-stderr-msg code-content-script stderr)))
            stdout)
          (throw (IllegalStateException. (nonzero-msg code-content-script exit-code (IOUtils/toString stdout "UTF-8") stderr)))))
      (catch IllegalArgumentException e
        (throw-execution-error! e))
      (catch IOException e
        (throw-execution-error! e))
      (catch InterruptedException e
        (throw-execution-error! e)))))
