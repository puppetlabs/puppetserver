(ns puppetlabs.services.puppet-admin.puppet-admin-core
  (:import (clojure.lang IFn))
  (:require [compojure.core :as compojure]
            [compojure.route :as route]
            [schema.core :as schema]
            [puppetlabs.puppetserver.ringutils :as ringutils]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def PuppetAdminSettings
  {:client-whitelist [schema/Str]
   (schema/optional-key :authorization-required) schema/Bool})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v1-routes
  "Routes for v1 of the Puppet Admin HTTP API."
  []
  (compojure/routes
    (compojure/GET "/hello" request
                   {:status 200 :body "hello"})))

(defn versioned-routes
  []
  (compojure/routes
    (compojure/context "/v1" []
                       (v1-routes))
    (route/not-found "Not Found")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate config->puppet-admin-settings :- PuppetAdminSettings
  "Given the full Puppet Server config map, extract and return the settings for
  the Puppet Admin web service."
  [config]
  (:puppet-admin config))

(schema/defn ^:always-validate build-ring-handler :- IFn
  "Return the ring handler for the Puppet Admin API."
  [path :- schema/Str
   settings :- PuppetAdminSettings]
  (-> (compojure/context
        path
        []
        (versioned-routes))
      (ringutils/wrap-with-cert-whitelist-check settings)))