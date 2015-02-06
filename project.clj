(def tk-version "1.0.1")
(def tk-jetty-version "1.1.1")
(def ks-version "1.0.0")

(defn deploy-info
  [url]
  { :url url
    :username :env/nexus_jenkins_username
    :password :env/nexus_jenkins_password
    :sign-releases false })

(defproject puppetlabs/pe-file-sync "0.0.1"
  :description "PE File Synchronization Services"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [puppetlabs/trapperkeeper ~tk-version]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/http-client "0.4.1"]
                 [compojure "1.1.8" :exclusions [commons-io]]
                 [commons-io "2.1"]
                 [liberator "0.12.0"]
                 [org.eclipse.jgit/org.eclipse.jgit.http.server
                    "3.4.1.201406201815-r" :exclusions [org.apache.httpcomponents/httpclient]]]

  :pedantic? :abort

  :test-paths ["test/unit" "test/integration"]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]

  :profiles {:dev {:source-paths  ["dev"]
                   :dependencies  [[org.clojure/tools.namespace "0.2.4"]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version]
                                   [puppetlabs/trapperkeeper-webserver-jetty9 ~tk-jetty-version :classifier "test"]
                                   [puppetlabs/trapperkeeper ~tk-version :classifier "test" :scope "test"]
                                   [puppetlabs/kitchensink ~ks-version :classifier "test" :scope "test"]
                                   [spyscope "0.1.4" :exclusions [clj-time]]]
                   :injections    [(require 'spyscope.core)]}}

  :test-selectors {:default (complement :integration)
                   :integration :integration
                   :unit (complement :integration)
                   :all (constantly true)}

  :repl-options {:init-ns user}
)
