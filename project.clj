(def tk-version "1.1.1")
(def tk-jetty-version "1.3.1")
(def ks-version "1.0.0")
(def jgit-version "3.7.0.201502260915-r")

(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(defproject puppetlabs/pe-file-sync "0.0.2"
  :description "PE File Synchronization Services"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/http-client "0.4.4" :exclusions [commons-io]]
                 [puppetlabs/ssl-utils "0.8.0"]
                 [prismatic/schema "0.4.0"]
                 [compojure "1.1.8"]
                 [overtone/at-at "1.2.0"]
                 [ring/ring-json "0.3.1"]
                 [org.eclipse.jgit/org.eclipse.jgit.http.server ~jgit-version
                  :exclusions [org.apache.httpcomponents/httpclient]]
                 [org.eclipse.jgit/org.eclipse.jgit.http.apache ~jgit-version
                  :exclusions [org.apache.httpcomponents/httpclient]]]

  :pedantic? :abort

  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :test-paths ["test/unit" "test/integration"]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.4"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                  [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                  [spyscope "0.1.4" :exclusions [clj-time]]]
                   :injections [(require 'spyscope.core)]}}

  :test-selectors {:integration :integration
                   :unit (complement :integration)}

  :main puppetlabs.trapperkeeper.main

  :repl-options {:init-ns user})
