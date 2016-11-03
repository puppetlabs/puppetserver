(ns puppetlabs.puppetserver.shell-utils
  (:require [schema.core :as schema]
            [clojure.java.io :as io]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.string :as string]
            [puppetlabs.i18n.core :as i18n])
  (:import (com.puppetlabs.puppetserver ShellUtils ShellUtils$ExecutionOptions)
           (java.io InputStream)
           (org.apache.commons.io IOUtils)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ExecutionResult
  "A map that contains the details of the result of executing a command."
  {:exit-code schema/Int
   :stderr schema/Str
   :stdout schema/Str})

(def ExecutionResultStreamed
  "A map that contains the details of the result of executing a command with
  stdout as a stream."
  {:exit-code schema/Int
   :stderr schema/Str
   :stdout InputStream})

(def ExecutionOptions
  {(schema/optional-key :args) [schema/Str]
   (schema/optional-key :env) (schema/maybe {schema/Str schema/Str})
   (schema/optional-key :in) (schema/maybe InputStream)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(def default-execution-options
  {:args []
   :env nil
   :in nil})

(schema/defn ^:always-validate java-exe-options :- ShellUtils$ExecutionOptions
  [{:keys [env in]} :- ExecutionOptions]
  (let [exe-options (ShellUtils$ExecutionOptions.)]
    (.setStdin exe-options in)
    (when env
      (.setEnv exe-options (ks/mapkeys name env)))
    exe-options))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  validate-command!
  "Checks the command string to ensure that it is an absolute path, executable
  and that the file exists. An exception is thrown if any of those are not the
  case."
  [command :- schema/Str]
  (let [command-file (io/as-file command)]
    (cond
      (not (.isAbsolute command-file))
      (throw (IllegalArgumentException.
              (i18n/trs "An absolute path is required, but ''{0}'' is not an absolute path" command)))
      (not (.exists command-file))
      (let [cmds (string/split command #" ")]
        (if (and (> (count cmds) 1) (.exists (io/as-file (first cmds))))
          (throw (IllegalArgumentException.
                  (i18n/trs "Command ''{0}'' appears to use command-line arguments, but this is not allowed." command)))
          (throw (IllegalArgumentException.
                  (i18n/trs "The referenced command ''{0}'' does not exist" command)))))
      (not (.canExecute command-file))
      (throw (IllegalArgumentException.
              (i18n/trs "The referenced command ''{0}'' is not executable" command))))))

(schema/defn ^:always-validate
  execute-command-streamed :- ExecutionResultStreamed
  "Execute the specified fully qualified command (string) and any included
  command-arguments (vector of strings) and return the exit-code (integer),
  and the contents of the stdout (stream) and stderr (string) for the command."
  ([command :- schema/Str]
   (execute-command-streamed command {}))
  ([command :- schema/Str
    opts :- ExecutionOptions]
   (let [{:keys [args] :as opts} (merge default-execution-options opts)]
     (validate-command! command)
     (let [process (ShellUtils/executeCommand
                    command
                    (into-array String args)
                    (java-exe-options opts))]
       {:exit-code (.getExitCode process)
        :stderr (.getError process)
        :stdout (.getOutputAsStream process)}))))

(schema/defn ^:always-validate
  execute-command :- ExecutionResult
  "Execute the specified fully qualified command (string) and any included
  command-arguments (vector of strings) and return the exit-code (integer),
  and the contents of the stdout (string) and stderr (string) for the command."
  ([command :- schema/Str]
   (execute-command command {}))
  ([command :- schema/Str
    opts :- ExecutionOptions]
   (let [result (execute-command-streamed command opts)]
     (update-in result [:stdout]
                (fn [stdout] (IOUtils/toString stdout "UTF-8"))))))
