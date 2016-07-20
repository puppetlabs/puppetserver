(ns puppetlabs.puppetserver.cli.subcommand
  (:require [puppetlabs.kitchensink.core :as ks]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.i18n.core :as i18n :refer [trs]]))

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
   Required arguments:
      --config <config file or directory>"
  [cli-args]
  (let [specs    [["-c" "--config CONFIG-PATH"
                   (trs "Path to a configuration file or directory of configuration files. See the documentation for a list of supported file types.")]]
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

(defn run
  "The main entry for subcommands, this parses the `args` provided on the
   command line and prints out a message if there's any missing or incorrect.
   The given `run-fn` is then called with the configuration map and the command
   line arguments specific to the subcommand.

   The `run-fn` must accept a configuration map and argument sequence."
  [run-fn args]
  (try+
    (let [[config extra-args help] (-> (or args '())
                                       (parse-cli-args!))]
      (run-fn (load-tk-config config) extra-args))
    (catch map? m
      (println (:msg m))
      (case (ks/without-ns (:kind m))
        :cli-error (System/exit 1)
        :cli-help  (System/exit 0)))
    (finally
      (shutdown-agents))))
