(def ps-version "2.8.1-SNAPSHOT")
(def tk-jetty9-version "1.8.1")

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

(defproject puppetlabs/puppetserver ps-version
  :description "Puppet Server"

  :min-lein-version "2.7.1"

  :parent-project {:coords [puppetlabs/clj-parent "0.1.7"]
                   :inherit [:managed-dependencies]}

  :dependencies [[org.clojure/clojure]

                 [slingshot]
                 ;; we need to exclude snakeyaml because JRuby also brings it in
                 [clj-yaml nil :exclusions [org.yaml/snakeyaml]]
                 [commons-lang]
                 [commons-io]

                 [clj-time]
                 [prismatic/schema]
                 [me.raynes/fs]
                 [liberator]
                 [org.apache.commons/commons-exec]

                 ;; We do not currently use this dependency directly, but
                 ;; we have documentation that shows how users can use it to
                 ;; send their logs to logstash, so we include it in the jar.
                 ;; we may use it directly in the future
                 ;; We are using an exlusion here because logback dependencies should
                 ;; be inherited from trapperkeeper to avoid accidentally bringing
                 ;; in different versions of the three different logback artifacts
                 [net.logstash.logback/logstash-logback-encoder]

                 ;; Override this version explicitly to remediate CVE-2017-15095
                 [com.fasterxml.jackson.core/jackson-databind "2.9.1"]
                 [com.fasterxml.jackson.core/jackson-core "2.9.1"]

                 [puppetlabs/jruby-utils "0.4.0"]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-authorization]
                 [puppetlabs/trapperkeeper-scheduler]
                 [puppetlabs/trapperkeeper-status]
                 [puppetlabs/trapperkeeper-filesystem-watcher "1.1.0"]
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

  :repositories [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-releases__local/"]
                 ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-snapshots__local/"]]

  :plugins [[lein-parent "0.3.1"]
            [puppetlabs/i18n "0.5.1"]]

  :uberjar-name "puppet-server-release.jar"
  :lein-ezbake {:vars {:user "puppet"
                       :group "puppet"
                       :build-type "foss"
                       :java-args "-Xms2g -Xmx2g -XX:MaxPermSize=256m"
                       :repo-target "PC1"
                       :bootstrap-source :services-d
                       :logrotate-enabled false}
                :resources {:dir "tmp/ezbake-resources"}
                :config-dir "ezbake/config"
                :system-config-dir "ezbake/system-config"}

  :deploy-repositories [["releases" ~(deploy-info "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-releases__local/")]
                        ["snapshots" ~(deploy-info "https://artifactory.delivery.puppetlabs.net/artifactory/list/clojure-snapshots__local/")]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:source-paths  ["dev"]
                   :dependencies  [[org.clojure/tools.namespace]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version :classifier "test"]
                                   [puppetlabs/trapperkeeper nil :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink nil :classifier "test" :scope "test"]
                                   [ring-basic-authentication]
                                   [ring-mock]
                                   [grimradical/clj-semver "0.3.0" :exclusions [org.clojure/clojure]]
                                   [beckon]]
                   ; SERVER-332, enable SSLv3 for unit tests that exercise SSLv3
                   :jvm-opts      ["-Djava.security.properties=./dev-resources/java.security"]}

             :testutils {:source-paths ^:replace ["test/unit" "test/integration"]}

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
                                               [puppetlabs/puppetserver ~ps-version]
                                               [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]
                                               [org.clojure/tools.nrepl nil]]
                      :plugins [[puppetlabs/lein-ezbake "1.1.11"]]
                      :name "puppetserver"}
             :uberjar {:aot [puppetlabs.trapperkeeper.main]
                       :dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty9-version]]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}
             :voom {:plugins [[lein-voom "0.1.0-20150115_230705-gd96d771" :exclusions [org.clojure/clojure]]]}}

  :test-selectors {:integration :integration
                   :unit (complement :integration)}

  :aliases {"gem" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem"]
            "ruby" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.ruby"]
            "irb" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.irb"]}

  ; tests use a lot of PermGen (jruby instances)
  :jvm-opts ["-XX:MaxPermSize=256m"
             ~(str "-Xms" (heap-size "1G" "min"))
             ~(str "-Xmx" (heap-size "2G" "max"))]

  :repl-options {:init-ns user}

  ;; NOTE: jruby-stdlib packages some unexpected things inside
  ;; of its jar.  e.g., it puts a pre-built copy of the bouncycastle
  ;; jar into its META-INF directory.  This is highly undesirable
  ;; for projects that already have a dependency on a different
  ;; version of bouncycastle.  Therefore, when building uberjars,
  ;; you should take care to exclude the things that you don't want
  ;; in your final jar.  Here is an example of how you could exclude
  ;; that from the final uberjar:
  :uberjar-exclusions [#"META-INF/jruby.home/lib/ruby/shared/org/bouncycastle"]
  )
