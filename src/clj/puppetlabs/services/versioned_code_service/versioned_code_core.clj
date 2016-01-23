(ns puppetlabs.services.versioned-code-service.versioned-code-core
  (:require [schema.core :as schema]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetserver.shell-utils :as shell-utils]
            [clojure.string :as string])
  (:import (java.io IOException)))

(schema/defn ^:always-validate execute-code-id-script! :- (schema/maybe schema/Str)
  [code-id-script :- schema/Str
   environment :- schema/Str]
  (let [error-msg #(log/errorf (str "Calculating code id generated an error. "
                                    "Command executed: '%s', error generated: '%s'")
                               code-id-script (.getMessage %1))]
    (try
      (let [{:keys [exit-code stderr stdout]} (shell-utils/execute-command
                                               code-id-script [environment])]
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
              (log/errorf (str "Error output generated while calculating code id. "
                               "Command executed: '%s', stderr: '%s'") code-id-script stderr))
            (string/trim-newline stdout))
          (do
            (log/errorf (str "Non-zero exit code returned while calculating code id. "
                             "Command executed: '%s', exit-code '%d', stdout: '%s', stderr: '%s'")
                        code-id-script exit-code stdout stderr)
            nil)))
      (catch IllegalArgumentException e
        (error-msg e)
        nil)
      (catch IOException e
        (error-msg e)
        nil)
      (catch InterruptedException e
        (error-msg e)
        nil))))
