(ns puppetlabs.services.versioned-code-service.versioned-code-core
  (:require [schema.core :as schema]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetserver.common :as ps-common]
            [puppetlabs.puppetserver.shell-utils :as shell-utils]
            [clojure.string :as string]
            [puppetlabs.i18n.core :as i18n])
  (:import (java.io IOException InputStream)
           (org.apache.commons.io IOUtils)))

(def VersionedCodeServiceConfig
  "Schema describing the versioned-code-service config settings"
  {(schema/optional-key :code-id-command) schema/Str
   (schema/optional-key :code-content-command) schema/Str})

(schema/defn ^:always-validate
  success-with-stderr-msg :- schema/Str
  [cmd :- schema/Str
   stderr :- schema/Str]
  (format "%s %s"
          (i18n/trs "Error output generated while running ''{0}''." cmd)
          (i18n/trs "stderr: ''{0}''" stderr)))

(schema/defn ^:always-validate
  nonzero-msg :- schema/Str
  [cmd :- schema/Str
   exit-code :- schema/Int
   stdout :- schema/Str
   stderr :- schema/Str]
  (format "%s %s"
          (i18n/trs "Non-zero exit code returned while running ''{0}''." cmd)
          (i18n/trs "exit-code: ''{0}'', stdout: ''{1}'', stderr: ''{2}''" exit-code stdout stderr)))

(schema/defn ^:always-validate
  execution-error-msg :- schema/Str
  [cmd :- schema/Str
   e :- Exception]
  (i18n/trs "Running script generated an error. Command executed: ''{0}'', error generated: ''{1}''"
              cmd (.getMessage e)))

(schema/defn ^:always-validate execute-code-id-script! :- schema/Str
  "Executes code-id-script to determine the code-id for environment.
  Non-zero return from code-id-script generates an IllegalStateException."
  [code-id-script :- schema/Str
   environment :- ps-common/Environment]
  (let [{:keys [exit-code stderr stdout]} (shell-utils/execute-command
                                           code-id-script
                                           {:args [environment]})]
    ; TODO Decide what to do about normalizing/sanitizing output with respect to
    ; control characters and encodings

    ;; There are three cases we care about here:
    ;; - exit code is 0 and no stderr generated: groovy. return stdout
    ;; - exit code is 0 and stderr was generated: that's fine. log an error
    ;;   about the stderr and return stdout.
    ;; - exit code is non-zero: oh no! throw an error with all the details
    (if (zero? exit-code)
      (do
        (when-not (string/blank? stderr)
          (log/error (success-with-stderr-msg code-id-script stderr)))
        (string/trim-newline stdout))
      (throw (IllegalStateException. (nonzero-msg code-id-script exit-code stdout stderr))))))

(schema/defn get-current-code-id! :- (schema/maybe String)
  "Execute the code-id-script and validate its output before returning"
  [code-id-script :- schema/Str
   environment :- schema/Str]
  (let [code-id (execute-code-id-script! code-id-script environment)]
    (when-not (nil? (schema/check (schema/maybe ps-common/CodeId) code-id))
      (throw (IllegalStateException. (ps-common/code-id-validation-error-msg code-id))))
    code-id))

(schema/defn ^:always-validate
  execute-code-content-script! :- InputStream
  "Given a string path to an executable script and the environment, code-id, and
  file-path of a desired revision of a file, returns that file as a stream. Does
  no exception catching: Being unable to compute code content is considered a
  fatal error and should be handled by calling code appropriately."
  [code-content-script :- schema/Str
   environment :- ps-common/Environment
   code-id :- ps-common/CodeId
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

(schema/defn ^:always-validate validate-config!
  [{:keys [code-id-command code-content-command]} :- (schema/maybe VersionedCodeServiceConfig)]
  "Validates the versioned-code-service config. The config is considered valid
  if it is either empty or fully populated."
  (when (or
    (and code-id-command (not code-content-command))
    (and (not code-id-command) code-content-command))
    (throw (IllegalStateException.
            (format "%s %s"
                    (i18n/trs "Only one of \"versioned-code.code-id-command\" and \"versioned-code.code-content-command\" was set.")
                    (i18n/trs "Both or neither must be set for the versioned-code-service to function correctly."))))))
