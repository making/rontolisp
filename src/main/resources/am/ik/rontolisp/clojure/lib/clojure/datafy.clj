(ns clojure.datafy
  "Functions to turn values into data and navigate from data, built into
  rontolisp, over the Datafiable and Navigable protocols of
  clojure.core.protocols. Written for this front end from the documented
  behaviour of Clojure's namespace of the same name."
  (:require [clojure.core.protocols :as p]
            [rontolisp.internal.datafy :as kernel]))

(defn datafy
  "x as data, through clojure.core.protocols/datafy. When that answers a new
  collection, its metadata holds x as :clojure.datafy/obj and the symbol of x's
  class as :clojure.datafy/class."
  [x]
  (let [v (p/datafy x)]
    (if (identical? v x)
      v
      (if (coll? v)
        (vary-meta v assoc ::obj x ::class (symbol (kernel/class-name-of x)))
        v))))

(defn nav
  "What v, found under k in coll (a value datafy answered), stands for,
  through clojure.core.protocols/nav."
  [coll k v]
  (p/nav coll k v))

(extend-protocol p/Datafiable
  Object
  (datafy [x]
    (if (instance? clojure.lang.IRef x)
      (with-meta [(deref x)] (meta x))
      x)))
