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

(defn environment
  "Return a map representing the environment suitable for use with Ruby
  subcommands.  Environment variables puppetserver needs are enforced while
  all other variables are preserved.  This function manages GEM_HOME,
  GEM_PATH, RUBYOPT, RUBY_OPTS, and RUBYLIB.

  If no initial-env is provided then System/getenv is used.

  The underlying principle is that a process should preserve as much of the
  environment as possible in an effort to get out of the end users way.  This
  includes PATH which should be fully managed elsewhere, e.g. the service
  management framework."
  ([config] (environment config (System/getenv)))
  ([config initial-env]
    (let [gem-home (get-in config [:jruby-puppet :gem-home])]
      (-> (into {} initial-env)
          (dissoc "GEM_PATH" "RUBYOPT" "RUBY_OPTS" "RUBYLIB")
          (assoc "GEM_HOME" gem-home
                 "JARS_NO_REQUIRE" "true")))))

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
      (println (:message m))
      (case (ks/without-ns (:type m))
        :cli-error (System/exit 1)
        :cli-help  (System/exit 0)))
    (finally
      (shutdown-agents))))
