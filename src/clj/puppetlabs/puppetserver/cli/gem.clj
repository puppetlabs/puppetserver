(ns puppetlabs.puppetserver.cli.gem
  (:import (org.jruby.embed ScriptingContainer))
  (:require [puppetlabs.kitchensink.core :as ks]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.trapperkeeper.config :as tk-config]))

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
      --config <config file or directory>"
  [cli-args]
  (let [specs       [["-c" "--config CONFIG-PATH"
                      (str "Path to a configuration file or directory of configuration files. "
                           "See the documentation for a list of supported file types.")]]
        required    [:config]]
    (ks/cli! cli-args specs required)))

(defn run!
  [config args]
  (doto (ScriptingContainer.)
    (.setArgv (into-array String args))
    (.setEnvironment (hash-map "GEM_HOME" (get-in config [:jruby-puppet :gem-home])))
    (.runScriptlet "load 'META-INF/jruby.home/bin/gem'")))

(defn load-tk-config
  [cli-data]
  (let [debug? (or (:debug cli-data) false)]
    (if-not (contains? cli-data :config)
      {:debug debug?}
      (-> (:config cli-data)
          (tk-config/load-config)
          (assoc :debug debug?)))))

(defn -main
  [& args]
  (try+
    (let [[config extra-args help] (-> (or args '())
                                       (parse-cli-args!))]
      (run! (load-tk-config config) extra-args))
    (catch map? m
      (println (:message m))
      (case (ks/without-ns (:type m))
        :cli-error (System/exit 1)
        :cli-help  (System/exit 0)))
    (finally
      (shutdown-agents))))