(ns clojure.core.protocols
  "The protocols Clojure's core functions extend, built into rontolisp:
  CollReduce, InternalReduce and IKVReduce, which reduce and reduce-kv hand a
  record, deftype or reify extending them to, and Datafiable and Navigable
  (extendable through metadata). Written for this front end from the
  documented behaviour of Clojure's namespace of the same name.")

(defprotocol CollReduce
  "Reduction of a collection by the collection itself: reduce, into,
  transduce and the other verbs built on reduce hand a record, deftype or
  reify extending it to its coll-reduce."
  (coll-reduce [coll f] [coll f val]
    "f folded over coll: from val, else from the first member, (f) of an
    empty coll."))

(defprotocol InternalReduce
  "Reduction of a seq from within."
  (internal-reduce [seq f start]
    "f folded over seq starting from start."))

(defprotocol IKVReduce
  "Reduction of a map over its keys and values."
  (kv-reduce [amap f init]
    "f folded over the entries of amap as (f acc key value), starting from init."))

(defprotocol Datafiable
  "Conversion of a value to data."
  :extend-via-metadata true
  (datafy [o]
    "o as data: nil and plain values answer themselves."))

(defprotocol Navigable
  "Navigation from a value taken from data to what it stands for."
  :extend-via-metadata true
  (nav [coll k v]
    "What v, found under k in coll, stands for: v itself unless extended."))

(extend-protocol CollReduce
  nil
  (coll-reduce
    ([_ f] (f))
    ([_ _ val] val))
  Object
  (coll-reduce
    ([coll f] (reduce f coll))
    ([coll f val] (reduce f val coll))))

(extend-protocol InternalReduce
  nil
  (internal-reduce [_ _ start] start)
  Object
  (internal-reduce [s f start] (reduce f start s)))

(extend-protocol IKVReduce
  nil
  (kv-reduce [_ _ init] init)
  Object
  (kv-reduce [amap f init] (reduce-kv f init amap)))

(extend-protocol Datafiable
  nil
  (datafy [_] nil)
  Object
  (datafy [x] x))

(extend-protocol Navigable
  Object
  (nav [_ _ x] x))
