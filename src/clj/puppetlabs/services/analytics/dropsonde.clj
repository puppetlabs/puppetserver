(ns puppetlabs.services.analytics.dropsonde
  (:require [clojure.java.shell :refer [sh]]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]))

(def puppet-agent-ruby "/opt/puppetlabs/puppet/bin/ruby")
(def dropsonde-dir "/opt/puppetlabs/server/data/puppetserver/dropsonde")
(def dropsonde-bin (str dropsonde-dir "/bin/dropsonde"))

(defn run-dropsonde
  [config]
  (let [confdir (get-in config [:jruby-puppet :master-conf-dir]
                        jruby-puppet/default-master-conf-dir)
        codedir (get-in config [:jruby-puppet :master-code-dir]
                        jruby-puppet/default-master-code-dir)
        vardir (get-in config [:jruby-puppet :master-var-dir]
                       jruby-puppet/default-master-var-dir)
        logdir (get-in config [:jruby-puppet :master-log-dir]
                       jruby-puppet/default-master-log-dir)
        result (sh puppet-agent-ruby dropsonde-bin "submit"
                   :env {"GEM_HOME" dropsonde-dir
                         "GEM_PATH" dropsonde-dir
                         "HOME" dropsonde-dir
                         "PUPPET_CONFDIF" confdir
                         "PUPPET_CODEDIR" codedir
                         "PUPPET_VARDIR" vardir
                         "PUPPET_LOGDIR" logdir})]
    (if (= 0 (:exit result))
      (log/info (i18n/trs "Successfully submitted module metrics via Dropsonde."))
      (log/warn (i18n/trs "Failed to submit module metrics via Dropsonde. Error: {0}"
                          (:err result))))))
