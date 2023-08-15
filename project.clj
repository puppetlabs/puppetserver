(def ps-version "8.2.1-SNAPSHOT")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false})

(def heap-size-from-profile-clj
  (let [profile-clj (io/file (System/getenv "HOME") ".lein" "profiles.clj")]
    (if (.exists profile-clj)
      (-> profile-clj
        slurp
        read-string
        (get-in [:user :puppetserver-heap-size])))))

(defn heap-size
  [default-heap-size]
  (or
    (System/getenv "PUPPETSERVER_HEAP_SIZE")
    heap-size-from-profile-clj
    default-heap-size))

(defproject puppetlabs/puppetserver ps-version
  :description "Puppet Server"

  :min-lein-version "2.9.1"

  :parent-project {:coords [puppetlabs/clj-parent "7.1.0"]
                   :inherit [:managed-dependencies]}

  :dependencies [[org.clojure/clojure]

                 [slingshot]
                 [org.yaml/snakeyaml]
                 [commons-lang]
                 [commons-io]

                 [clj-time]
                 [grimradical/clj-semver "0.3.0" :exclusions [org.clojure/clojure]]
                 [prismatic/schema]
                 [clj-commons/fs]
                 [liberator]
                 [org.apache.commons/commons-exec]
                 [io.dropwizard.metrics/metrics-core]
                 [org.yaml/snakeyaml "2.0"]

                 ;; We do not currently use this dependency directly, but
                 ;; we have documentation that shows how users can use it to
                 ;; send their logs to logstash, so we include it in the jar.
                 [net.logstash.logback/logstash-logback-encoder]

                 [puppetlabs/jruby-utils]
                 [puppetlabs/clj-shell-utils]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-webserver-jetty9]
                 [puppetlabs/trapperkeeper-authorization]
                 [puppetlabs/trapperkeeper-comidi-metrics]
                 [puppetlabs/trapperkeeper-metrics]
                 [puppetlabs/trapperkeeper-scheduler]
                 [puppetlabs/trapperkeeper-status]
                 [puppetlabs/trapperkeeper-filesystem-watcher]
                 [puppetlabs/kitchensink]
                 [puppetlabs/ssl-utils]
                 [puppetlabs/ring-middleware]
                 [puppetlabs/dujour-version-check]
                 [puppetlabs/http-client]
                 [puppetlabs/comidi]
                 [puppetlabs/i18n]
                 [puppetlabs/rbac-client]]

  :main puppetlabs.trapperkeeper.main

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]

  :test-paths ["test/unit" "test/integration"]
  :resource-paths ["resources" "src/ruby"]

  :repositories [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/"]
                 ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"]]

  :plugins [[lein-parent "0.3.7"]
            [jonase/eastwood "1.2.2" :exclusions [org.clojure/clojure]]
            ;; We have to have this, and it needs to agree with clj-parent
            ;; until/unless you can have managed plugin dependencies.
            [puppetlabs/i18n "0.9.2" :hooks false]]

  :uberjar-name "puppet-server-release.jar"
  :lein-ezbake {:vars {:user "puppet"
                       :group "puppet"
                       :numeric-uid-gid 52
                       :build-type "foss"
                       :puppet-platform-version 8
                       :java-args ~(str "-Xms2g -Xmx2g "
                                     "-Djruby.logger.class=com.puppetlabs.jruby_utils.jruby.Slf4jLogger")
                       :create-dirs ["/opt/puppetlabs/server/data/puppetserver/jars"]
                       :repo-target "puppet8"
                       :nonfinal-repo-target "puppet8-nightly"
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

  :profiles {:defaults {:source-paths  ["dev"]
                        :dependencies  [[org.clojure/tools.namespace]
                                        [puppetlabs/trapperkeeper-webserver-jetty9 :classifier "test"]
                                        [puppetlabs/trapperkeeper nil :classifier "test" :scope "test"]
                                        [puppetlabs/trapperkeeper-metrics :classifier "test" :scope "test"]
                                        [puppetlabs/kitchensink nil :classifier "test" :scope "test"]
                                        [ring-basic-authentication]
                                        [ring/ring-mock]
                                        [beckon]
                                        [lambdaisland/uri "1.4.70"]
                                        [puppetlabs/rbac-client :classifier "test" :scope "test"]]}
             :dev [:defaults
                   {:dependencies [[org.bouncycastle/bcpkix-jdk18on]]}]
             :fips [:defaults
                    {:dependencies [[org.bouncycastle/bcpkix-fips]
                                    [org.bouncycastle/bc-fips]
                                    [org.bouncycastle/bctls-fips]]
                     :jvm-opts ~(let [version (System/getProperty "java.specification.version")
                                      [major minor _] (clojure.string/split version #"\.")
                                      unsupported-ex (ex-info "Unsupported major Java version."
                                                       {:major major
                                                        :minor minor})]
                                  (condp = (java.lang.Integer/parseInt major)
                                    1 (if (= 8 (java.lang.Integer/parseInt minor))
                                        ["-Djava.security.properties==./dev-resources/java.security.jdk8-fips"]
                                        (throw unsupported-ex))
                                    11 ["-Djava.security.properties==./dev-resources/java.security.jdk11on-fips"]
                                    17 ["-Djava.security.properties==./dev-resources/java.security.jdk11on-fips"]
                                    (throw unsupported-ex)))}]

             :testutils {:source-paths ["test/unit" "test/integration"]}
             :test {
                    ;; NOTE: In core.async version 0.2.382, the default size for
                    ;; the core.async dispatch thread pool was reduced from
                    ;; (42 + (2 * num-cpus)) to... eight.  The jruby metrics tests
                    ;; use core.async and need more than eight threads to run
                    ;; properly; this setting overrides the default value.  Without
                    ;; it the metrics tests will hang.
                    :jvm-opts ["-Dclojure.core.async.pool-size=50"]
                    ;; Use humane test output so you can actually see what the problem is
                    ;; when a test fails.
                    :dependencies [[pjstadig/humane-test-output "0.8.3"]]
                    :injections [(require 'pjstadig.humane-test-output)
                                 (pjstadig.humane-test-output/activate!)]}


             :ezbake {:dependencies ^:replace [;; we need to explicitly pull in our parent project's
                                               ;; clojure version here, because without it, lein
                                               ;; brings in its own version.
                                               ;; NOTE that these deps will completely replace the deps
                                               ;; in the list above, so any version overrides need to be
                                               ;; specified in both places. TODO: fix this.
                                               [org.clojure/clojure]
                                               [org.bouncycastle/bcpkix-jdk18on]
                                               [puppetlabs/jruby-utils]
                                               [puppetlabs/puppetserver ~ps-version]
                                               [puppetlabs/trapperkeeper-webserver-jetty9]]
                      :plugins [[puppetlabs/lein-ezbake "2.5.3"]]
                      :name "puppetserver"}
             :uberjar {:dependencies [[org.bouncycastle/bcpkix-jdk18on]
                                      [puppetlabs/trapperkeeper-webserver-jetty9]]
                       :aot [puppetlabs.trapperkeeper.main
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
                             puppetlabs.services.protocols.legacy-routes]}
             :ci {:plugins [[lein-pprint "1.1.1"]
                            [lein-exec "0.3.7"]]}}

  :test-selectors {:default (complement :multithreaded-only)
                   :integration :integration
                   :unit (complement :integration)
                   :multithreaded (complement :single-threaded-only)
                   :singlethreaded (complement :multithreaded-only)}

  :eastwood {:exclude-linters [:unused-meta-on-macro
                               :reflection
                               [:suspicious-test :second-arg-is-not-string]]
             :continue-on-exception true}

  :aliases {"gem" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem" "--config" "./dev/puppetserver.conf" "--"]
            "ruby" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.ruby" "--config" "./dev/puppetserver.conf" "--"]
            "irb" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.irb" "--config" "./dev/puppetserver.conf" "--"]
            "thread-test" ["trampoline" "run" "-b" "ext/thread_test/bootstrap.cfg" "--config" "./ext/thread_test/puppetserver.conf"]}

  :jvm-opts ~(let [version (System/getProperty "java.specification.version")
                   [major minor _] (clojure.string/split version #"\.")]
               (concat
                 ["-Djruby.logger.class=com.puppetlabs.jruby_utils.jruby.Slf4jLogger"
                  "-XX:+UseG1GC"
                  (str "-Xms" (heap-size "1G"))
                  (str "-Xmx" (heap-size "2G"))
                  "-XX:+IgnoreUnrecognizedVMOptions"]
                 (if (= 17 (java.lang.Integer/parseInt major))
                   ["--add-opens" "java.base/sun.nio.ch=ALL-UNNAMED" "--add-opens" "java.base/java.io=ALL-UNNAMED"]
                   [])))

  :repl-options {:init-ns dev-tools}
  :uberjar-exclusions  [#"META-INF/jruby.home/lib/ruby/stdlib/org/bouncycastle"
                        #"META-INF/jruby.home/lib/ruby/stdlib/org/yaml/snakeyaml"]

  ;; This is used to merge the locales.clj of all the dependencies into a single
  ;; file inside the uberjar
  :uberjar-merge-with {"locales.clj"  [(comp read-string slurp)
                                       (fn [new prev]
                                         (if (map? prev) [new prev] (conj prev new)))
                                       #(spit %1 (pr-str %2))]})

