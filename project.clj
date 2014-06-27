(def tk-version "0.4.2")
(def tk-jetty-version "0.5.2")
(def ks-version "0.7.1")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(defproject puppetlabs/jvm-puppet "0.1.5-SNAPSHOT"
  :description "JVM Puppet Master."

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/certificate-authority "0.2.2"]
                 [puppetlabs/http-client "0.1.7"]
                 [org.jruby/jruby-complete "1.7.10"]
                 [clj-time "0.5.1"]
                 [compojure "1.1.6"]
                 [me.raynes/fs "1.4.5"]
                 [prismatic/schema "0.2.1"]]

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

  :profiles {:dev {:test-paths    ["test-resources"]
                   :dependencies  [[org.clojure/tools.namespace "0.2.4"]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                   [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink ~ks-version
                                    :classifier "test" :scope "test"]
                                   [ring-mock "0.1.5"]
                                   [spyscope "0.1.4"]]
                   :injections    [(require 'spyscope.core)]}

             :uberjar {:aot [puppetlabs.trapperkeeper.main]}
             :ci {:plugins [[lein-pprint "1.1.1"]]}}

  ; tests use a lot of PermGen (jruby instances)
  :jvm-opts ["-XX:MaxPermSize=256m"]
  )
