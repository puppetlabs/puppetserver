(ns puppetlabs.puppetserver.ring.middleware.params
  (:require [ring.util.codec :as codec]
            [ring.util.request :as req]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COPY OF RELEVANT FUNCTIONS FROM UPSTREAM ring.middleware.params LIBRARY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-params [params encoding]
  (let [params (codec/form-decode params encoding)]
    (if (map? params) params {})))

(defn content-type
  "Return the content-type of the request, or nil if no content-type is set."
  [request]
  (if-let [type (get-in request [:headers "content-type"])]
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
                (let [params (parse-params (slurp body :encoding encoding) encoding)]
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
