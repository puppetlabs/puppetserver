(ns puppetlabs.services.analytics.dropsonde
  (:require [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetserver.shell-utils :as shell-utils]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet]))

(def puppet-agent-ruby "/opt/puppetlabs/puppet/bin/ruby")
(def dropsonde-dir "/opt/puppetlabs/server/data/puppetserver/dropsonde")
(def dropsonde-bin (str dropsonde-dir "/bin/dropsonde"))

(defn run-dropsonde
  [config]
  ;; process config to ensure default resolution of these settings
  (let [puppet-config (jruby-puppet/initialize-puppet-config
                       {}
                       (jruby-puppet/extract-puppet-config (:jruby-puppet config))
                       false)
        confdir (:server-conf-dir puppet-config)
        codedir (:server-code-dir puppet-config)
        vardir (:server-var-dir puppet-config)
        logdir (:server-log-dir puppet-config)
        result (shell-utils/execute-command puppet-agent-ruby
                                            {:args [dropsonde-bin "submit"]
                                             :env {"GEM_HOME" dropsonde-dir
                                                   "GEM_PATH" dropsonde-dir
                                                   "HOME" dropsonde-dir
                                                   "PUPPET_CONFDIF" confdir
                                                   "PUPPET_CODEDIR" codedir
                                                   "PUPPET_VARDIR" vardir
                                                   "PUPPET_LOGDIR" logdir}})]
    (if (= 0 (:exit-code result))
      (log/info (i18n/trs "Successfully submitted module metrics via Dropsonde."))
      (log/warn (i18n/trs "Failed to submit module metrics via Dropsonde. Error: {0}"
                          (:stderr result))))))
