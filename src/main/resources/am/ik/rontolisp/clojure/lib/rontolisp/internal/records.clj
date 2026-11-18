(ns rontolisp.internal.records
  "What a record's expansion calls that no class here has, built into
  rontolisp: the statics clojure.lang.APersistentMap/mapEquals, mapHash and
  mapHasheq, which a deftype* of a defrecord's expansion -- a type
  implementing IRecord -- reaches through its equals, hashCode and hasheq.
  Loaded where such a call first lowers. Written for this front end from the
  documented behaviour of the members of the same names.")

(defn map-equals
  "APersistentMap/mapEquals: o is m, or a map of m's count holding each of m's
  keys with an equal value."
  [m o]
  (cond (identical? m o) true
        (not (instance? java.util.Map o)) false
        (not= (count m) (count o)) false
        :else (every? (fn [e] (and (contains? o (key e)) (= (val e) (get o (key e))))) m)))

(defn- hash-code
  "The hashCode of x, 0 for nil."
  [x]
  (if (nil? x) 0 (.hashCode ^Object x)))

(defn map-hash
  "APersistentMap/mapHash: each entry's key hashCode xor its value's, summed as
  an int."
  [m]
  (reduce (fn [h e] (unchecked-add-int h (bit-xor (hash-code (key e)) (hash-code (val e))))) (int 0) m))

(defn map-hasheq
  "APersistentMap/mapHasheq: the unordered hash of m's entries."
  [m]
  (hash-unordered-coll m))
