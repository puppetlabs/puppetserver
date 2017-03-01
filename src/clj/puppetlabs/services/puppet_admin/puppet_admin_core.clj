(ns puppetlabs.services.puppet-admin.puppet-admin-core
  (:import (clojure.lang IFn))
  (:require [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.ringutils :as ringutils]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-puppet]
            [puppetlabs.puppetserver.liberator-utils :as liberator-utils]
            [schema.core :as schema]
            [liberator.core :refer [defresource]]
            ;[liberator.dev :as liberator-dev]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.puppetserver.ring.middleware.params :as pl-ring-params]
            [puppetlabs.i18n.core :as i18n]
            [slingshot.slingshot :as sling]
            [ring.util.response :as rr]
            [clojure.tools.logging :as log]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def PuppetAdminSettings
  ringutils/WhitelistSettings)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Liberator resources

(defresource environment-cache-resource
  [jruby-service env-name]
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
    (if env-name
      (jruby-puppet/mark-environment-expired! jruby-service env-name)
      (jruby-puppet/mark-all-environments-expired! jruby-service))))

(defn handle-jruby-pool-flush
  [jruby-service]
  (sling/try+
    (jruby-puppet/flush-jruby-pool! jruby-service)
    ; 204 No Content on success
    {:status 204}
    ; If a lock timeout occurs return a 503 Service Unavailable
    (catch [:kind :puppetlabs.services.jruby-pool-manager.impl.jruby-agents/jruby-lock-timeout] e
      (log/error (:msg e))
      (-> (rr/response (:msg e))
          (rr/status 503)
          (rr/content-type "text/plain")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v1-routes
  [jruby-service]
  "Routes for v1 of the Puppet Admin HTTP API."
  (comidi/routes
    (comidi/ANY "/environment-cache" request
      (environment-cache-resource jruby-service
        (get-in request [:query-params "environment"])))
    (comidi/DELETE "/jruby-pool" []
      (handle-jruby-pool-flush jruby-service))))

(defn versioned-routes
  [jruby-service]
  (comidi/routes
    (comidi/context "/v1" (v1-routes jruby-service))
    (comidi/not-found "Not Found")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate config->puppet-admin-settings
  :- (schema/maybe PuppetAdminSettings)
  "Given the full Puppet Server config map, extract and return the Puppet
  Admin web service settings."
  [config]
  (:puppet-admin config))

(schema/defn ^:always-validate puppet-admin-settings->whitelist-settings
  :- ringutils/WhitelistSettings
  "Given the full Puppet Admin web service settings, extract and return the
  embedded client whitelist settings."
  [admin-settings :- (schema/maybe PuppetAdminSettings)]
  (select-keys admin-settings [:client-whitelist
                               :authorization-required]))

(schema/defn ^:always-validate build-ring-handler :- IFn
  "Returns the ring handler for the Puppet Admin API."
  [path :- schema/Str
   settings :- ringutils/WhitelistSettings
   jruby-service :- (schema/protocol jruby-puppet/JRubyPuppetService)
   authorization-fn :- IFn]
  (-> (versioned-routes jruby-service)
      ((partial comidi/context path))
      (comidi/routes->handler)
      ;(liberator-dev/wrap-trace :header)           ; very useful for debugging!
      ;; For backward compatibility, requests to the puppet-admin endpoint
      ;; will be authorized by a client-whitelist, if one is configured for
      ;; 'certificate_status'.  When we are able to drop support for
      ;; client-whitelist authorization later on, we should be able to get rid
      ;; of the 'wrap-with-trapperkeeper-or-client-whitelist-authorization'
      ;; function and replace with a line chaining the handler into a call to
      ;; 'authorization-fn'.
      (ringutils/wrap-with-trapperkeeper-or-client-whitelist-authorization
        authorization-fn
        path
        settings)
      i18n/locale-negotiator
      pl-ring-params/wrap-params))
