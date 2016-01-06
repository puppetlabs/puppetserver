(ns puppetlabs.puppetserver.cli.config
  (:import [com.typesafe.config ConfigUtil]
           [com.typesafe.config.parser ConfigDocumentFactory ConfigDocument])
  (:require [cheshire.core :as json]
            [puppetlabs.puppetserver.cli.subcommand :as cli]
            [slingshot.slingshot :refer [throw+]]))

(defn read-config-settings!
  [config cmd-args]
  (let [as-json? (some #{"--as-json"} cmd-args)
        settings (remove #{"--as-json"} cmd-args)
        settings-values (map #(->> (ConfigUtil/splitPath %)
                                   (map keyword)
                                   (get-in config)
                                   (vector %))
                             settings)]
    (if (= 1 (count settings-values))
      (println (second (first settings-values)))
      (if as-json?
        (->> settings-values
             flatten
             (apply hash-map)
             json/generate-string
             println)
        (doseq [[setting value] settings-values]
          (println setting "=" value))))))

(defn write-config-settings!
  [config-files settings-values]
  (let [settings-values (partition 2 settings-values)
        docs (map #(vector % (ConfigDocumentFactory/parseFile %)) config-files)
        get-section #(-> % ConfigUtil/splitPath butlast ConfigUtil/joinPath)]
    (doseq [[file doc] docs]
      (->> settings-values
           (filter #(.hasPath doc (get-section (first %))))
           (reduce (fn [doc [k v]] (.withValueText doc k v)) doc)
           (.render)
           (spit file)))))

(defn config-command
  [{:keys [config config-files]} cmd-args]
  (let [action (first cmd-args)]
    (case action
      "print" (read-config-settings! config (rest cmd-args))
      "set"   (write-config-settings! config-files (rest cmd-args))
      (throw+ {:type ::cli-error
               :message (str "Unsupported command: " action)}))))

(defn -main
  "Subcommand for reading and writing puppet server configuration settings on
  the command line. Only supports HOCON files.

  Formatting and comments in configuration files are preserved during write
  operations.

  EXAMPLES

  Get a configuration setting:
    $ puppetserver config print jruby-puppet.master-code-dir
    /etc/puppetlabs/code

  Get multiple configuration settings:
    $ puppetserver config print jruby-puppet.master-code-dir jruby-puppet.master-conf-dir
    jruby-puppet.master-code-dir = /etc/puppetlabs/code
    jruby-puppet.master-conf-dir = /etc/puppetlabs/puppet

  Get multiple configuration settings as a JSON structure:
    $ puppetserver config print jruby-puppet.master-code-dir jruby-puppet.master-conf-dir --as-json
    {'jruby-puppet.master-code-dir':'/etc/puppetlabs/code','jruby-puppet.master-conf-dir':'/etc/puppetlabs/puppet'}

  Change (or add) a configuration setting:
    $ puppetserver config set profiler.enabled true

  Change (or add) multiple configuration settings:
    $ puppetserver config set profiler.enabled true jruby-puppet.gem-home /my/gems
  "
  [& args]
  (cli/run config-command args))
