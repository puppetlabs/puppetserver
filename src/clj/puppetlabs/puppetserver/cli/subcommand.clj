(ns puppetlabs.puppetserver.cli.subcommand
  (:require [puppetlabs.kitchensink.core :as ks]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.i18n.core :as i18n]))

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
   Required arguments:
      --config <config file or directory>"
  [cli-args]
  (let [specs    [["-c" "--config CONFIG-PATH"
                   (i18n/trs "Path to a configuration file or directory of configuration files. See the documentation for a list of supported file types.")]]
        required [:config]]
    (ks/cli! cli-args specs required)))

(defn load-tk-config
  [cli-data]
  (let [debug? (or (:debug cli-data) false)]
    (if-not (contains? cli-data :config)
      {:debug debug?}
      (-> (:config cli-data)
          (tk-config/load-config)
          (assoc :debug debug?)))))

(defn print-message-and-exit
  [error-map exit-code]
  (if-let [msg (:msg error-map)]
    (println msg)
    (println error-map))
  (System/exit exit-code))

(defn run
  "The main entry for subcommands, this parses the `args` provided on the
   command line and prints out a message if there's any missing or incorrect.
   The given `run-fn` is then called with the configuration map and the command
   line arguments specific to the subcommand.

   The `run-fn` must accept a configuration map and argument sequence."
  [run-fn args]
  (try+
    (let [[config extra-args] (-> (or args '())
                                  (parse-cli-args!))]
      (run-fn (load-tk-config config) extra-args))
    (catch map? m
      (let [kind (:kind m)]
        (if (keyword? kind)
          (case (ks/without-ns kind)
            :cli-error (print-message-and-exit m 1)
            :cli-help (print-message-and-exit m 0)
            nil)))
      (throw+))
    (finally
      (shutdown-agents))))

(defn jruby-run
  "Executes a function that invokes the JRuby interpeter with the `run` function and
  use the return value from JRuby as the process exit code."
  [run-fn args]
  (-> (run run-fn args)
      .getStatus
      System/exit))
