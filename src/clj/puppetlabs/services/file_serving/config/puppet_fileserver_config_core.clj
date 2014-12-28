(ns ^{:doc
      "Tools to parse Puppet fileserver.conf."}
    puppetlabs.services.file-serving.config.puppet-fileserver-config-core
  (:require
    [puppetlabs.kitchensink.core :as ks]
    [schema.core :as schema]
    [puppetlabs.puppetserver.ringutils :as ring])
  (:use [clojure.java.io :only (reader)]
        [clojure.string :only (split trim split)]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; internal helpers

(defn- parse-one-line
  [s]
  (if (= (first s) \[)
    (-> s (subs 1 (.indexOf s "]")) trim keyword)
    (let [n (.indexOf s " ")]
      (if (neg? n)
        (throw (Exception. (str "Could not parse: " s)))
        (let [directive (-> s (subs 0 n) trim keyword)
              argument (-> s (subs (inc n)) trim)]
          (if (= :path directive)
            {:path argument}
            {:acl [directive argument]}))))))

(defn- strip-comment
  [s]
  (let [n (.indexOf s (int \#))]
    (if (and (not (neg? n)))
      (subs s 0 n)
      s)))

(defn- mapify
  [coll]
  (loop [xs coll m {} key nil]
    (if-let [x (first xs)]
      (if (map? x)
        (recur (rest xs)
               (ks/deep-merge-with into m (assoc {} key x))
               key)
        (recur (rest xs)
               (assoc m x {})
               x))
      m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas

(def Mount {:path schema/Str :acl [(schema/pair (schema/either (schema/eq :allow) (schema/eq :deny)) "acl" schema/Str "value")]})
(def Mounts {schema/Str Mount})
(def MountPath [(schema/one Mount "mount") (schema/one schema/Str "path")])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public API


;; TODO make that schema
(defn fileserver-parse
  "Parse the given filename as if it was a fileserver.conf, and returns
  a map whose keys are mounts."
  [filename]
  {:pre [(or (string? filename)
             (instance? java.io.File filename))]}
  (with-open [r (reader filename)]
    (->> (line-seq r)
         (map strip-comment)
         (remove (fn [s] (every? #(Character/isWhitespace %) s)))
         (map parse-one-line)
         mapify)))

(schema/defn find-mount :- MountPath
  "find a given mount from a path."
  [mounts :- Mounts
   path :- schema/Str]
  (let [s (split path #"/" 2)
        mount (keyword (first s))
        relative-path (second s)]
    (if (nil? mount)
      (throw (Exception. (str "No mount in: " path)))
      (if (not (contains? mounts mount))
        (throw (Exception. (str "Unknown mount: " mount)))
        [(mounts mount) relative-path])
  )))

(schema/defn allowed? :- schema/Bool
  "check a given request is allowed."
  [request :- ring/RingRequest
   mount :- Mount]
  ; TODO, implement real acl checks
  true)

