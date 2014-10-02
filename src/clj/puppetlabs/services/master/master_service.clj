(ns puppetlabs.services.master.master-service
  (:import (java.io FileInputStream))
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [compojure.core :as compojure]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.services.master.master-core :as core]
            [puppetlabs.puppetserver.certificate-authority :as ca]))

(defservice master-service
  [[:WebserverService add-ring-handler]
   [:PuppetServerConfigService get-config]
   [:RequestHandlerService handle-request]]
  (init
   [this context]
   (when (.exists (io/as-file "/proc/meminfo"))
     (let [heap-size (/ (.maxMemory (Runtime/getRuntime)) 1024)
           mem-info-file (FileInputStream. "/proc/meminfo")
           mem-size (Integer. (second (re-find #"MemTotal:\s+(\d+)\s+\S+"
                                               (slurp mem-info-file))))]
       (.close mem-info-file)
       (when (< (* 0.9 mem-size) heap-size)
         (throw (IllegalStateException. "Error: Not enough RAM. Puppet Server requires at least 2GB of RAM.")))))
   (let [path            ""
         config          (get-config)
         master-certname (get-in config [:puppet-server :certname])
         master-settings (ca/config->master-settings config)
         ca-settings     (ca/config->ca-settings config)]

     ; TODO - https://tickets.puppetlabs.com/browse/PE-3929
     ; The master needs to eventually get these files from the CA server
     ; via http or git or something.
     (ca/initialize! ca-settings master-settings master-certname)

     (log/info "Master Service adding a ring handler")
     (add-ring-handler
      (compojure/context path [] (core/compojure-app handle-request))
      path))
   context))
