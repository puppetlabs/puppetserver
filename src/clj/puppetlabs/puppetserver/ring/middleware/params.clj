(ns puppetlabs.puppetserver.ring.middleware.params
  (:require [ring.util.codec :as codec]
            [ring.util.request :as req]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COPY OF RELEVANT FUNCTIONS FROM UPSTREAM ring.middleware.params LIBRARY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; This namespace is basically just here to provide an implementation of
;; the `params-request` middleware function that supports a String representation
;; of the body of a request.  The upstream library requires the body to be of
;; a type that is compatible with Clojure's IOFactory, which forces us to read
;; the request body into memory twice for requests that we have to pass down
;; into the JRuby layer.  (Technically, the Ring specification states that the
;; body must be an InputStream, so the maintainer of the upstream library was
;; reluctant to accept any sort of upstream PR to work around this issue.)
;;
;; All of this code is copied from the upstream library, and there is just
;; one very slight modification (see comment in `assoc-form-params` function)
;; that allows us to avoid reading the body into memory twice.
;;
;; In the future, if we can handle the query parameter parsing strictly on the
;; Clojure side and remove that code from the Ruby side, we should be able to
;; get rid of this.  That will be much easier to consider doing once we're able
;; to get rid of the Rack/Webrick support.
;;
;; If that happens, we should delete this namespace :)
;;

(defn parse-params [params encoding]
  (let [params (codec/form-decode params encoding)]
    (if (map? params) params {})))

(defn content-type
  "Return the content-type of the request, or nil if no content-type is set."
  [request]
  ;; NOTE: in the latest version of ring-core, they only look in
  ;;  the headers map for the content type.  They no longer fall
  ;;  back to looking for it in the main request map.
  (if-let [type (or (get-in request [:headers "content-type"])
                    (get request :content-type))]
    (second (re-find #"^(.*?)(?:;|$)" type))))

(defn urlencoded-form?
  "True if a request contains a urlencoded form in the body."
  [request]
  (if-let [^String type (content-type request)]
    (.startsWith type "application/x-www-form-urlencoded")))

(defn assoc-query-params
  "Parse and assoc parameters from the query string with the request."
  [request encoding]
  (merge-with merge request
              (if-let [query-string (:query-string request)]
                (let [params (parse-params query-string encoding)]
                  {:query-params params, :params params})
                {:query-params {}, :params {}})))

(defn assoc-form-params
  "Parse and assoc parameters from the request body with the request."
  [request encoding]
  (merge-with merge request
              (if-let [body (and (urlencoded-form? request) (:body request))]
                (let [params (parse-params
                               ;; NOTE: this is the main difference between our
                               ;;  copy of this code and the upstream version:
                               ;;  the upstream always does a slurp here, while
                               ;;  we only do a slurp if the body is not already
                               ;;  a string.
                               (if (string? body)
                                 body
                                 (slurp body :encoding encoding))
                               encoding)]
                  {:form-params params, :params params})
                {:form-params {}, :params {}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn params-request
  "Adds parameters from the query string and the request body to the request
  map. See: wrap-params."
  {:arglists '([request] [request options])}
  [request & [opts]]
  (let [encoding (or (:encoding opts)
                     (req/character-encoding request)
                     "UTF-8")
        request  (if (:form-params request)
                   request
                   (assoc-form-params request encoding))]
    (if (:query-params request)
      request
      (assoc-query-params request encoding))))
