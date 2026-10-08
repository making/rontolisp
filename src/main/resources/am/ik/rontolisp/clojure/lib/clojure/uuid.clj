(ns clojure.uuid
  "The #uuid tagged literal's namespace, built into rontolisp: the literal reads
  through the reader itself, and default-uuid-reader, the private reader
  default-data-readers names, through the same reader."
  (:require [rontolisp.internal.uuid :as kernel]))

(defn- default-uuid-reader
  "The java.util.UUID the string form spells: what #uuid reads."
  [form]
  (kernel/read-uuid form))
