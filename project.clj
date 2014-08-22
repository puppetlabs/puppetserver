(def tk-version "0.4.3")
(def tk-jetty-version "0.6.1")
(def ks-version "0.7.2")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(defproject puppetlabs/puppet-server "0.1.10-SNAPSHOT"
  :description "Puppet Server"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/certificate-authority "0.4.0"]
                 [puppetlabs/http-client "0.2.4"]
                 [org.jruby/jruby-complete "1.7.13"]
                 [clj-time "0.5.1"]
                 [compojure "1.1.6" :exclusions [org.clojure/tools.macro]]
                 [me.raynes/fs "1.4.5"]
                 [prismatic/schema "0.2.1"]
                 [commons-lang "2.6"]
                 [commons-io "2.4"]
                 [slingshot "0.10.3"]]

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

  :profiles {:dev {:dependencies  [[org.clojure/tools.namespace "0.2.4"]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                   [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                   [ring-mock "0.1.5"]
                                   [spyscope "0.1.4" :exclusions [clj-time]]]
                   :injections    [(require 'spyscope.core)]}

             :uberjar {:aot [puppetlabs.trapperkeeper.main]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}

  :aliases {"go" ["trampoline" "run" "--config" "./dev-resources/puppet-server.conf" "--bootstrap-config" "./dev-resources/bootstrap.cfg"]}

  ; tests use a lot of PermGen (jruby instances)
  :jvm-opts ["-XX:MaxPermSize=256m"]
  )
