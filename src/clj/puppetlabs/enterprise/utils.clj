(ns puppetlabs.enterprise.utils
  (:require [clj-time.format :as time-format]
            [clj-time.core :as time]))

(def datetime-formatter
  "The date/time formatter used to produce timestamps using clj-time.
  This matches the format used by PuppetDB."
  (time-format/formatters :date-time))

(defn format-date-time
  "Given a DateTime object, return a human-readable, formatted string."
  [date-time]
  (time-format/unparse datetime-formatter date-time))

(defn timestamp
  "Returns a nicely-formatted string of the current date/time."
  []
  (format-date-time (time/now)))
