(ns puppetlabs.puppetserver.jruby-request
  (:require [clojure.tools.logging :as log]
            [ring.util.response :as ring-response]
            [slingshot.slingshot :as sling]
            [puppetlabs.ring-middleware.core :as middleware]
            [puppetlabs.ring-middleware.utils :as ringutils]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-puppet]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [schema.core :as schema]
            [puppetlabs.puppetserver.common :as ps-common]
            [puppetlabs.i18n.core :as i18n :refer [trs tru]]))

(defn jruby-timeout?
  "Determine if the supplied slingshot message is for a JRuby borrow timeout."
  [x]
  (when (map? x)
    (= (:kind x)
       :puppetlabs.services.jruby-pool-manager.jruby-core/jruby-timeout)))

(defn output-error
  [{:keys [uri]} {:keys [msg]} http-status]
  (log/error (trs "Error {0} on SERVER at {1}: {2}" http-status uri msg))
  (ringutils/plain-response http-status msg))

(defn wrap-with-error-handling
  "Middleware that wraps a JRuby request with some error handling to return
  the appropriate http status codes, etc."
  [handler]
  (fn [request]
    (sling/try+
     (handler request)
     (catch ringutils/bad-request? e
       (output-error request e 400))
     (catch jruby-timeout? e
       (output-error request e 503))
     (catch ringutils/service-unavailable? e
       (output-error request e 503)))))

(defn wrap-with-jruby-instance
  "Middleware fn that borrows a jruby instance from the `jruby-service` and makes
  it available in the request as `:jruby-instance`"
  [handler jruby-service]
  (fn [request]
    (jruby/with-jruby-puppet
     jruby-puppet jruby-service {:request (dissoc request :ssl-client-cert)}
     (handler (assoc request :jruby-instance jruby-puppet)))))

(defn get-environment-from-request
  "Gets the environment from the URL or query string of a request."
  [req]
  ;; If environment is derived from the path, favor that over a query/form
  ;; param named environment, since it doesn't make sense to ask about
  ;; environment production in environment development.
  (or (get-in req [:route-params :environment])
      (get-in req [:params "environment"])))

(defn wrap-with-environment-validation
  "Middleware function which validates the presence and syntactical content
  of an environment in a ring request.  If validation fails, a :bad-request
  slingshot exception is thrown."
  [handler]
  (fn [request]
    (let [environment (get-environment-from-request request)]
      (cond
        (nil? environment)
        (ringutils/throw-bad-request!
         (tru "An environment parameter must be specified"))

        (not (nil? (schema/check ps-common/Environment environment)))
        (ringutils/throw-bad-request!
         (ps-common/environment-validation-error-msg environment))

        :else
        (handler request)))))
