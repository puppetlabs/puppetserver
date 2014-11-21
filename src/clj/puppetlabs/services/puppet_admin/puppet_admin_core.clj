(ns puppetlabs.services.puppet-admin.puppet-admin-core
  (:import (clojure.lang IFn))
  (:require [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-puppet]
            [puppetlabs.puppetserver.liberator-utils :as liberator-utils]
            [schema.core :as schema]
            [compojure.core :as compojure]
            [compojure.route :as route]
            [liberator.core :refer [defresource]]
            [liberator.dev :as liberator-dev]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def PuppetAdminSettings
  {:client-whitelist [schema/Str]
   (schema/optional-key :authorization-required) schema/Bool})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Liberator resource

(defresource environment-cache-resource
  [jruby-service]
  :allowed-methods [:delete]

  ;; If you need to define :available-media-types, see comment below.
  ;:available-media-types ...

  :handle-exception liberator-utils/exception-handler

  ;; This next line of code tells liberator to ignore any media-types
  ;; the client has asked for.  This is necessary for this endpoint to work
  ;; when the client sends an 'Accept: */*' header, due to the somewhat strange
  ;; fact that this endpoint defines no 'available-media-types', since it always
  ;; returns a '204 No Content' on success.
  ;;
  ;; If this resource is ever updated to define ':available-media-types' and
  ;; return a response body, this line of code should be deleted.
  :media-type-available? true

  ;; Never return a '201 Created', we're not creating anything
  :new? false

  :delete!
  (fn [context]
    (jruby-puppet/mark-all-environments-expired! jruby-service)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v1-routes
  [jruby-service]
  "Routes for v1 of the Puppet Admin HTTP API."
  (compojure/routes
    (compojure/ANY "/environment-cache" []
      (environment-cache-resource jruby-service))))

(defn versioned-routes
  [jruby-service]
  (compojure/routes
    (compojure/context "/v1" [] (v1-routes jruby-service))
    (route/not-found "Not Found")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate config->puppet-admin-settings :- PuppetAdminSettings
  "Given the full Puppet Server config map, extract and return the settings for
  the Puppet Admin web service."
  [config]
  (:puppet-admin config))

(schema/defn ^:always-validate build-ring-handler :- IFn
  "Returns the ring handler for the Puppet Admin API."
  [path :- schema/Str
   settings :- PuppetAdminSettings
   jruby-service :- jruby-puppet/JRubyPuppetService]
  (-> (compojure/context
        path
        []
        (versioned-routes jruby-service))
      ;(liberator-dev/wrap-trace :header)           ; very useful for debugging!
      (ringutils/wrap-with-cert-whitelist-check settings)))
