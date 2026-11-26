(ns clojure.instant
  "Reading RFC 3339 timestamps into instants, built into rontolisp. Written for
  this front end from the documented behaviour of Clojure's namespace of the
  same name."
  (:require [rontolisp.internal.instant :as kernel]))

(defn parse-timestamp
  "Matches the timestamp cs -- yyyy, then optionally -MM, -dd, Thh, :mm, :ss and
  a fraction of a second, each needing the ones before it, then optionally Z or
  an offset +hh:mm or -hh:mm -- and calls new-instant with ten integers: years,
  months, days, hours, minutes, seconds, nanoseconds, the offset's sign (-1, 0
  or 1), hours and minutes. A missing month or day is 1, any other missing
  number 0; a fraction counts its first nine digits. Anything else is refused."
  [new-instant cs]
  (apply new-instant (kernel/parse cs)))

(defn validated
  "Wraps the instant constructor new-instance so that it refuses the ten
  integers parse-timestamp passes unless each is in range: a month 1 to 12, a
  day within its month, hours to 23, minutes to 59, seconds to 59 (60 when the
  minutes are 59), nanoseconds to 999999999, an offset sign -1 to 1, offset
  hours to 23 and minutes to 59."
  [new-instance]
  (fn [years months days hours minutes seconds nanoseconds offset-sign offset-hours offset-minutes]
    (kernel/validate years months days hours minutes seconds nanoseconds offset-sign offset-hours
                     offset-minutes)
    (new-instance years months days hours minutes seconds nanoseconds offset-sign offset-hours
                  offset-minutes)))

(defn read-instant-date
  "The java.util.Date the timestamp cs spells, its offset folded into UTC: what
  #inst reads."
  [cs]
  (kernel/read-date cs))

(defn read-instant-calendar
  "The java.util.Calendar the timestamp cs spells, which keeps its offset."
  [cs]
  (kernel/read-calendar cs))

(defn read-instant-timestamp
  "The java.sql.Timestamp the timestamp cs spells, its offset folded into UTC,
  which keeps the nanoseconds of its fraction."
  [cs]
  (kernel/read-timestamp cs))
