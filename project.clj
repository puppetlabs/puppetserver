(def clj-version "1.7.0")
(def tk-version "1.4.0")
(def tk-jetty-version "1.5.9")
(def ks-version "1.3.1")
(def ps-version "2.6.0-master-SNAPSHOT")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(defproject puppetlabs/puppetserver ps-version
  :description "Puppet Server"

  :dependencies [[org.clojure/clojure ~clj-version]

                 ;; begin version conflict resolution dependencies
                 [puppetlabs/typesafe-config "0.1.5"]
                 [org.clojure/tools.macro "0.1.5"]
                 [org.clojure/tools.reader "1.0.0-beta1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [ring/ring-servlet "1.4.0"]
                 [hiccup "1.0.5"]
                 ;; end version conflict resolution dependencies

                 [cheshire "5.6.1"]
                 [slingshot "0.12.2"]
                 [clj-yaml "0.4.0" :exclusions [org.yaml/snakeyaml]]
                 [commons-lang "2.6"]
                 [commons-io "2.4"]
                 [clj-time "0.11.0"]
                 [prismatic/schema "1.1.1"]
                 [me.raynes/fs "1.4.6"]
                 [liberator "0.12.0"]
                 [org.apache.commons/commons-exec "1.3"]

                 [org.jruby/jruby-core "1.7.20.1"
                  :exclusions [com.github.jnr/jffi com.github.jnr/jnr-x86asm]]
                 ;; jffi and jnr-x86asm are explicit dependencies because,
                 ;; in JRuby's poms, they are defined using version ranges,
                 ;; and :pedantic? :abort won't tolerate this.
                 [com.github.jnr/jffi "1.2.9"]
                 [com.github.jnr/jffi "1.2.9" :classifier "native"]
                 [com.github.jnr/jnr-x86asm "1.0.2"]
                 ;; NOTE: jruby-stdlib packages some unexpected things inside
                 ;; of its jar; please read the detailed notes above the
                 ;; 'uberjar-exclusions' example toward the end of this file.
                 [org.jruby/jruby-stdlib "1.7.20.1"]

                 ;; we do not currently use this dependency directly, but
                 ;; we have documentation that shows how users can use it to
                 ;; send their logs to logstash, so we include it in the jar.
                 ;; we may use it directly in the future
                 [net.logstash.logback/logstash-logback-encoder "4.5.1"]


                 [puppetlabs/jruby-utils "0.2.0"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/trapperkeeper-authorization "0.7.0"]
                 [puppetlabs/trapperkeeper-scheduler "0.0.1"]
                 [puppetlabs/trapperkeeper-status "0.3.5"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/ssl-utils "0.8.1"]
                 [puppetlabs/ring-middleware "1.0.0"]
                 [puppetlabs/dujour-version-check "0.1.2" :exclusions [org.clojure/tools.logging]]
                 [puppetlabs/http-client "0.5.0"]
                 [puppetlabs/comidi "0.3.1"]
                 [puppetlabs/i18n "0.4.1"]]

  :main puppetlabs.trapperkeeper.main

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/unit" "test/integration"]
  :resource-paths ["resources" "src/ruby"]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]
            [puppetlabs/i18n "0.4.1"]]

  :uberjar-name "puppet-server-release.jar"
  :lein-ezbake {:vars {:user "puppet"
                       :group "puppet"
                       :build-type "foss"
                       :java-args "-Xms2g -Xmx2g -XX:MaxPermSize=256m"
                       :repo-target "PC1"
                       :bootstrap-source :services-d}
                :resources {:dir "tmp/ezbake-resources"}
                :config-dir "ezbake/config"
                :system-config-dir "ezbake/system-config"}

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:source-paths  ["dev"]
                   :dependencies  [[org.clojure/tools.namespace "0.2.10"]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                   [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                   [ring-basic-authentication "1.0.5"]
                                   [ring-mock "0.1.5"]
                                   [spyscope "0.1.4" :exclusions [clj-time]]
                                   [grimradical/clj-semver "0.3.0" :exclusions [org.clojure/clojure]]
                                   [beckon "0.1.1"]]
                   :injections    [(require 'spyscope.core)]
                   ; SERVER-332, enable SSLv3 for unit tests that exercise SSLv3
                   :jvm-opts      ["-Djava.security.properties=./dev-resources/java.security"]}

             :testutils {:source-paths ^:replace ["test/unit" "test/integration"]}

             :ezbake {:dependencies ^:replace [;; we need to explicitly specify the clojure version
                                               ;; here, because without it, lein brings in its own
                                               ;; version, and older versions of lein (such as version
                                               ;; 2.5.1 that is used on our jenkins servers at the time
                                               ;; of this writing) depend on clojure 1.6.
                                               [org.clojure/clojure ~clj-version]
                                               [puppetlabs/puppetserver ~ps-version]
                                               [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                               [org.clojure/tools.nrepl "0.2.3"]]
                      :plugins [[puppetlabs/lein-ezbake "0.4.4"]]
                      :name "puppetserver"}
             :uberjar {:aot [puppetlabs.trapperkeeper.main]
                       :dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}
             :voom {:plugins [[lein-voom "0.1.0-20150115_230705-gd96d771" :exclusions [org.clojure/clojure]]]}}

  :test-selectors {:integration :integration
                   :unit (complement :integration)}

  :aliases {"gem" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem"]
            "ruby" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.ruby"]
            "irb" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.irb"]}

  ; tests use a lot of PermGen (jruby instances)
  :jvm-opts ["-XX:MaxPermSize=256m" "-Xmx2g"]

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
