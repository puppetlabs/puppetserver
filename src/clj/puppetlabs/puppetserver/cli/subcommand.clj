(ns puppetlabs.puppetserver.cli.subcommand
  (:require [puppetlabs.kitchensink.core :as ks]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.trapperkeeper.config :as tk-config]))

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
   Required arguments:
      --config <config file or directory>"
  [cli-args]
  (let [specs    [["-c" "--config CONFIG-PATH"
                   (str "Path to a configuration file or directory of "
                        "configuration files. See the documentation for "
                        "a list of supported file types.")]]
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

   The `run-fn` must accept a configuration map and argument sequence.
   The configuration map will look like:
    {
      :config       {<the fully parsed & merged configuration map>}
      :config-path  '/opt/puppetlabs/server/app/conf.d/'
      :config-files [<File foo.conf>, <File bar.conf>, <File qux.conf>]
    }
  "
  [run-fn args]
  (try+
   (let [[config extra-args help] (parse-cli-args! (or args '()))]
     (run-fn {:config (load-tk-config config)
              :config-path (:config config)
              :config-files (tk-config/get-files-from-config (:config config))}
             extra-args))
   (catch map? m
     (println (:message m))
     (case (ks/without-ns (:type m))
       :cli-error (System/exit 1)
       :cli-help  (System/exit 0)))
   (finally
     (shutdown-agents))))
