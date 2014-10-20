(def tk-version "0.5.1")
(def tk-jetty-version "0.9.0")
(def ks-version "0.7.2")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(defproject puppetlabs/puppet-server "0.3.1-SNAPSHOT"
  :description "Puppet Server"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/certificate-authority "0.6.0"]
                 [puppetlabs/http-client "0.2.9-SNAPSHOT"]
                 [org.jruby/jruby-core "1.7.15" :exclusions [com.github.jnr/jffi com.github.jnr/jnr-x86asm]]
                 [org.jruby/jruby-stdlib "1.7.15"]
                 [com.github.jnr/jffi "1.2.7"]
                 [com.github.jnr/jffi "1.2.7" :classifier "native"]
                 [com.github.jnr/jnr-x86asm "1.0.2"]
                 [clj-time "0.5.1" :exclusions [joda-time]]
                 [compojure "1.1.8" :exclusions [org.clojure/tools.macro]]
                 [liberator "0.12.0"]
                 [me.raynes/fs "1.4.5"]
                 [prismatic/schema "0.2.2"]
                 [commons-lang "2.6"]
                 [commons-io "2.4"]
                 [clj-yaml "0.4.0" :exclusions [org.yaml/snakeyaml]]
                 [slingshot "0.10.3"]
                 [ring/ring-codec "1.0.0"]
                 [cheshire "5.3.1"]
                 [trptcolin/versioneer "0.1.0"]]

  :main puppetlabs.trapperkeeper.main

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :resource-paths ["resources" "src/ruby"]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :plugins [[lein-release "1.0.5"]]
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
                                   [spyscope "0.1.4" :exclusions [clj-time]]]
                   :injections    [(require 'spyscope.core)]}

             :uberjar {:aot [puppetlabs.trapperkeeper.main]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}

  :aliases {"gem" ["trampoline" "run" "-m" "puppetlabs.puppetserver.cli.gem"]}

  ; tests use a lot of PermGen (jruby instances)
  :jvm-opts ["-XX:MaxPermSize=256m"]
  )
