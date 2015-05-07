(def tk-version "1.1.0")
(def tk-jetty-version "1.3.0")
(def ks-version "1.0.0")
(def ps-version "2.1.0-SNAPSHOT")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(defproject puppetlabs/puppetserver ps-version
  :description "Puppet Server"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/ssl-utils "0.8.0"]
                 [puppetlabs/http-client "0.4.3"]
                 [puppetlabs/dujour-version-check "0.1.2" :exclusions [org.clojure/tools.logging]]
                 [org.jruby/jruby-core "1.7.19"
                  :exclusions
                  [com.github.jnr/jffi com.github.jnr/jnr-x86asm com.github.jnr/jnr-ffi
                   org.ow2.asm/asm org.ow2.asm/asm-commons org.ow2.asm/asm-analysis
                   org.ow2.asm/asm-util com.github.jnr/jnr-constants]]
                 ;; NOTE: the JRuby poms (as of 1.7.18) had some conflicting transitive dependencies
                 ;; which necessitated the above exclusions and the following explicit versions of
                 ;; those transitive deps.  We should check to see if this issue is resolved
                 ;; in 1.7.19.
                 [com.github.jnr/jffi "1.2.7"]
                 [com.github.jnr/jffi "1.2.7" :classifier "native"]
                 [com.github.jnr/jnr-x86asm "1.0.2"]
                 [com.github.jnr/jnr-ffi "2.0.1"]
                 [com.github.jnr/jnr-constants "0.8.6"]
                 ;; NOTE: jruby-stdlib packages some unexpected things inside
                 ;; of its jar; please read the detailed notes above the
                 ;; 'uberjar-exclusions' example toward the end of this file.
                 [org.jruby/jruby-stdlib "1.7.19"]
                 [joda-time "2.5"]
                 [clj-time "0.6.0"]
                 [ring/ring-core "1.3.2"]
                 [compojure "1.1.8" :exclusions [org.clojure/tools.macro]]
                 [liberator "0.12.0"]
                 [me.raynes/fs "1.4.5"]
                 [prismatic/schema "0.2.2"]
                 [commons-lang "2.6"]
                 [commons-io "2.4"]
                 [commons-codec "1.9"]
                 [clj-yaml "0.4.0" :exclusions [org.yaml/snakeyaml]]
                 [slingshot "0.10.3"]
                 [cheshire "5.3.1"]
                 [trptcolin/versioneer "0.1.0"]]

  :main puppetlabs.trapperkeeper.main

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/unit" "test/integration"]
  :resource-paths ["resources" "src/ruby"]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]]

  :uberjar-name "puppet-server-release.jar"
  :lein-ezbake {:vars {:user "puppet"
                       :group "puppet"
                       :start-timeout "120"
                       :build-type "foss"
                       :java-args "-Xms2g -Xmx2g -XX:MaxPermSize=256m"
                       :repo-target "PC1"}
                :resources {:dir "tmp/ezbake-resources"}
                :config-dir "ezbake/config"}

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  :profiles {:dev {:source-paths  ["dev"]
                   :dependencies  [[org.clojure/tools.namespace "0.2.4"]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                   [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                   [ring-basic-authentication "1.0.5"]
                                   [ring-mock "0.1.5"]
                                   [spyscope "0.1.4" :exclusions [clj-time]]
                                   [grimradical/clj-semver "0.3.0" :exclusions [org.clojure/clojure]]]
                   :injections    [(require 'spyscope.core)]
                   ; SERVER-332, enable SSLv3 for unit tests that exercise SSLv3
                   :jvm-opts      ["-Djava.security.properties=./dev-resources/java.security"]}

             :ezbake {:dependencies ^:replace [[puppetlabs/puppetserver ~ps-version]
                                               [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                               [org.clojure/tools.nrepl "0.2.3"]]
                      :plugins [[puppetlabs/lein-ezbake "0.3.3"]]
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
  :jvm-opts ["-XX:MaxPermSize=256m"]

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
