(ns puppetlabs.puppetserver.shell-utils
  (:require [schema.core :as schema]
            [clojure.java.io :as io])
  (:import (com.puppetlabs.puppetserver ExecutionStubImpl)
           (java.io IOException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ExecutionResult
  "A map that contains the details of the result of executing a command."
  {:exit-code schema/Int
   :stderr schema/Str
   :stdout schema/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(schema/defn ^:private ^:always-validate
  validate-command!
  "Checks the command string to ensure that it is an absolute path, executable
  and that the file exists. An exception is thrown if any of those are not the
  case."
  [command :- schema/Str]
  (let [command-file (io/as-file command)]
    (cond
      (not (.isAbsolute command-file))
      (throw (IllegalArgumentException.
              (format "An absolute path is required, but '%s' is not an absolute path" command)))
      (not (.exists command-file))
      (throw (IllegalArgumentException.
              (format "The referenced command '%s' does not exist" command)))
      (not (.canExecute command-file))
      (throw (IllegalArgumentException.
              (format "The referenced command '%s' is not executable" command))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  execute-command :- ExecutionResult
  "Execute the specified fully qualified command (string) and any included
  command-arguments (vector of strings) and return the exit-code (integer),
  and the contents of the stdout (string) and stderr (string) for the command."
  ([command :- schema/Str]
   (execute-command command []))
  ([command :- schema/Str
    command-arguments :- [schema/Str]]
   (validate-command! command)
   (try
     (let [process (ExecutionStubImpl/executeCommand command (into-array String command-arguments))]
       {:exit-code (.getExitCode process)
        :stderr (.getError process)
        :stdout (.getOutput process)})
     (catch IOException e
       (throw (IllegalStateException. (format "Exception while executing '%s': %s" command (.getMessage e))))))))