(def ps-version "6.1.1-SNAPSHOT")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(def heap-size-from-profile-clj
  (let [profile-clj (io/file (System/getenv "HOME") ".lein" "profiles.clj")]
    (if (.exists profile-clj)
      (-> profile-clj
        slurp
        read-string
        (get-in [:user :puppetserver-heap-size])))))

(defn heap-size
  [default-heap-size heap-size-type]
  (or
    (System/getenv "PUPPETSERVER_HEAP_SIZE")
    heap-size-from-profile-clj
    (do
      (println "Using" default-heap-size heap-size-type
        "heap since not set via PUPPETSERVER_HEAP_SIZE environment variable or"
        "user.puppetserver-heap-size in ~/.lein/profiles.clj file. Set to at"
        "least 5G for best performance during test runs.")
      default-heap-size)))

(def figwheel-version "0.3.7")
(def cljsbuild-version "1.1.7")
(def clojurescript-version "1.10.238")

(defproject puppetlabs/puppetserver ps-version
  :description "Puppet Server"

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent "2.6.0"]
                   :inherit [:managed-dependencies]}

  :dependencies [[org.clojure/clojure]

                 ;; See SERVER-2216
                 [org.clojure/tools.nrepl "0.2.13"]

                 [slingshot]
                 [circleci/clj-yaml]
                 [org.yaml/snakeyaml]
                 [commons-lang]
                 [commons-io]

                 [clj-time]
                 [prismatic/schema]
                 [me.raynes/fs]
                 [liberator]
                 [org.apache.commons/commons-exec]
                 [io.dropwizard.metrics/metrics-core]
                 [com.fasterxml.jackson.module/jackson-module-afterburner]

                 ;; We do not currently use this dependency directly, but
                 ;; we have documentation that shows how users can use it to
                 ;; send their logs to logstash, so we include it in the jar.
                 ;; we may use it directly in the future
                 ;; We are using an exlusion here because logback dependencies should
                 ;; be inherited from trapperkeeper to avoid accidentally bringing
                 ;; in different versions of the three different logback artifacts
                 [net.logstash.logback/logstash-logback-encoder]

                 [puppetlabs/jruby-utils "2.1.0"]
                 [puppetlabs/jruby-deps "9.2.0.0-1"]

                 ;; JRuby 1.7.x and trapperkeeper (via core.async) both bring in
                 ;; asm dependencies.  Deferring to clj-parent to resolve the version.
                 [org.ow2.asm/asm-all]

                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-authorization]
                 [puppetlabs/trapperkeeper-comidi-metrics]
                 [puppetlabs/trapperkeeper-metrics]
                 [puppetlabs/trapperkeeper-scheduler]
                 [puppetlabs/trapperkeeper-status]
                 [puppetlabs/kitchensink]
                 [puppetlabs/ssl-utils]
                 [puppetlabs/ring-middleware]
                 [puppetlabs/dujour-version-check]
                 [puppetlabs/http-client]
                 [puppetlabs/comidi]
                 [puppetlabs/i18n]]

  :main puppetlabs.trapperkeeper.main

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]

  :test-paths ["test/unit" "test/integration"]
  :resource-paths ["resources" "src/ruby"]

  :repositories [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/"]
                 ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"]]

  :plugins [[lein-parent "0.3.1"]
            [puppetlabs/i18n "0.8.0"]]

  :uberjar-name "puppet-server-release.jar"
  :lein-ezbake {:vars {:user "puppet"
                       :group "puppet"
                       :build-type "foss"
                       :java-args ~(str "-Xms2g -Xmx2g "
                                     "-Djruby.logger.class=com.puppetlabs.jruby_utils.jruby.Slf4jLogger")
                       :create-dirs ["/opt/puppetlabs/server/data/puppetserver/jars"]
                       :repo-target "puppet6"
                       :nonfinal-repo-target "puppet6-nightly"
                       :bootstrap-source :services-d
                       :logrotate-enabled false}
                :resources {:dir "tmp/ezbake-resources"}
                :config-dir "ezbake/config"
                :system-config-dir "ezbake/system-config"}

  :deploy-repositories [["releases" ~(deploy-info "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/")]
                        ["snapshots" ~(deploy-info "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/")]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:source-paths  ["dev"]
                   :dependencies  [[org.clojure/tools.namespace]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 nil]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 nil :classifier "test"]
                                   [puppetlabs/trapperkeeper nil :classifier "test" :scope "test"]
                                   [puppetlabs/trapperkeeper-metrics :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink nil :classifier "test" :scope "test"]
                                   [ring-basic-authentication]
                                   [ring/ring-mock]
                                   [grimradical/clj-semver "0.3.0" :exclusions [org.clojure/clojure]]
                                   [beckon]
                                   [com.cemerick/url "0.1.1"]]

                   ;; SERVER-332, enable SSLv3 for unit tests that exercise SSLv3
                   :jvm-opts      ["-Djava.security.properties=./dev-resources/java.security"]}

             :testutils {:source-paths ["test/unit" "test/integration"]}
             :test {
                    ;; NOTE: In core.async version 0.2.382, the default size for
                    ;; the core.async dispatch thread pool was reduced from
                    ;; (42 + (2 * num-cpus)) to... eight.  The jruby metrics tests
                    ;; use core.async and need more than eight threads to run
                    ;; properly; this setting overrides the default value.  Without
                    ;; it the metrics tests will hang.
                    :jvm-opts ["-Dclojure.core.async.pool-size=50"]
                    }

             :ezbake {:dependencies ^:replace [;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
                                               ;; NOTE: we need to explicitly pass in `nil` values
                                               ;; for the version numbers here in order to correctly
                                               ;; inherit the versions from our parent project.
                                               ;; This is because of a bug in lein 2.7.1 that
                                               ;; prevents the deps from being processed properly
                                               ;; with `:managed-dependencies` when you specify
                                               ;; dependencies in a profile.  See:
                                               ;; https://github.com/technomancy/leiningen/issues/2216
                                               ;; Hopefully we can remove those `nil`s (if we care)
                                               ;; and this comment when lein 2.7.2 is available.
                                               ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

                                               ;; we need to explicitly pull in our parent project's
                                               ;; clojure version here, because without it, lein
                                               ;; brings in its own version, and older versions of
                                               ;; lein depend on clojure 1.6.
                                               [org.clojure/clojure nil]
                                               ;; I honestly don't know why we should need this
                                               ;; But building with ezbake is consistently failing and
                                               ;; pulling in an old build of clojurescript without it.
                                               [org.clojure/clojurescript ~clojurescript-version]
                                               [puppetlabs/puppetserver ~ps-version]
                                               [puppetlabs/trapperkeeper-webserver-jetty9 nil]
                                               [org.clojure/tools.nrepl nil]]
                      :plugins [[puppetlabs/lein-ezbake "1.9.3"]]
                      :name "puppetserver"}
             :uberjar {:aot [puppetlabs.trapperkeeper.main
                             puppetlabs.trapperkeeper.services.status.status-service
                             puppetlabs.trapperkeeper.services.metrics.metrics-service
                             puppetlabs.services.protocols.jruby-puppet
                             puppetlabs.trapperkeeper.services.watcher.filesystem-watch-service
                             puppetlabs.trapperkeeper.services.webserver.jetty9-service
                             puppetlabs.trapperkeeper.services.webrouting.webrouting-service
                             puppetlabs.services.legacy-routes.legacy-routes-core
                             puppetlabs.services.protocols.jruby-metrics
                             puppetlabs.services.protocols.ca
                             puppetlabs.puppetserver.common
                             puppetlabs.trapperkeeper.services.scheduler.scheduler-service
                             puppetlabs.services.jruby.jruby-metrics-core
                             puppetlabs.services.jruby.jruby-metrics-service
                             puppetlabs.services.protocols.puppet-server-config
                             puppetlabs.puppetserver.liberator-utils
                             puppetlabs.services.puppet-profiler.puppet-profiler-core
                             puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service
                             puppetlabs.services.jruby.puppet-environments
                             puppetlabs.services.jruby.jruby-puppet-schemas
                             puppetlabs.services.jruby.jruby-puppet-core
                             puppetlabs.services.jruby.jruby-puppet-service
                             puppetlabs.puppetserver.jruby-request
                             puppetlabs.puppetserver.shell-utils
                             puppetlabs.puppetserver.ringutils
                             puppetlabs.puppetserver.certificate-authority
                             puppetlabs.services.ca.certificate-authority-core
                             puppetlabs.puppetserver.ring.middleware.params
                             puppetlabs.services.puppet-admin.puppet-admin-core
                             puppetlabs.services.puppet-admin.puppet-admin-service
                             puppetlabs.services.versioned-code-service.versioned-code-core
                             puppetlabs.services.ca.certificate-authority-disabled-service
                             puppetlabs.services.protocols.request-handler
                             puppetlabs.services.request-handler.request-handler-core
                             puppetlabs.puppetserver.cli.subcommand
                             puppetlabs.services.request-handler.request-handler-service
                             puppetlabs.services.protocols.versioned-code
                             puppetlabs.services.protocols.puppet-profiler
                             puppetlabs.services.puppet-profiler.puppet-profiler-service
                             puppetlabs.services.master.master-core
                             puppetlabs.services.protocols.master
                             puppetlabs.services.config.puppet-server-config-core
                             puppetlabs.services.config.puppet-server-config-service
                             puppetlabs.services.versioned-code-service.versioned-code-service
                             puppetlabs.services.legacy-routes.legacy-routes-service
                             puppetlabs.services.master.master-service
                             puppetlabs.services.ca.certificate-authority-service
                             puppetlabs.puppetserver.cli.ruby
                             puppetlabs.puppetserver.cli.irb
                             puppetlabs.puppetserver.cli.gem
                             puppetlabs.services.analytics.analytics-service
                             puppetlabs.services.protocols.legacy-routes]
                       :dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 nil]]}
             :ci {:plugins [[lein-pprint "1.1.1"]
                            [lein-exec "0.3.7"]]}
             :voom {:plugins [[lein-voom "0.1.0-20150115_230705-gd96d771" :exclusions [org.clojure/clojure]]]}}

  :test-selectors {:integration :integration
                   :unit (complement :integration)}

  :aliases {"gem" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem" "--config" "./dev/puppetserver.conf" "--"]
            "ruby" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.ruby" "--config" "./dev/puppetserver.conf" "--"]
            "irb" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.irb" "--config" "./dev/puppetserver.conf" "--"]}

  :jvm-opts ["-Djruby.logger.class=com.puppetlabs.jruby_utils.jruby.Slf4jLogger"
               "-XX:+UseG1GC"
               ~(str "-Xms" (heap-size "1G" "min"))
               ~(str "-Xmx" (heap-size "2G" "max"))
               "-XX:+IgnoreUnrecognizedVMOptions"]

  :repl-options {:init-ns dev-tools}

  ;; This is used to merge the locales.clj of all the dependencies into a single
  ;; file inside the uberjar
  :uberjar-merge-with {"locales.clj"  [(comp read-string slurp)
                                       (fn [new prev]
                                         (if (map? prev) [new prev] (conj prev new)))
                                       #(spit %1 (pr-str %2))]}
  )
