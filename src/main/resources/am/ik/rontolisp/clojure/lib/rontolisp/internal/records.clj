(ns rontolisp.internal.records
  "What a record's expansion calls that no class here has, built into
  rontolisp: the statics clojure.lang.APersistentMap/mapEquals, mapHash and
  mapHasheq, clojure.lang.RecordIterator's construction and clojure.core's
  private imap-cons, which a deftype* of the oracle's defrecord expansion -- a
  type implementing IRecord -- reaches through its own methods. Loaded where
  such a call first lowers. Written for this front end from the documented
  behaviour of the members of the same names.")

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

(defn imap-cons
  "clojure.core/imap-cons: a map entry or a two-member vector assoc'd through
  this's assoc, anything else walked as entries, each assoc'd in turn."
  [this o]
  (cond (map-entry? o) (.assoc this (key o) (val o))
        (vector? o) (.assoc this (nth o 0) (nth o 1))
        :else (loop [this this o o]
                (if (seq o)
                  (let [pair (first o)]
                    (recur (.assoc this (key pair) (val pair)) (rest o)))
                  this))))

(defn record-iterator
  "clojure.lang.RecordIterator: each of the fields with what rec's valAt
  answers, then what the iterator ext steps."
  [rec fields ext]
  (clojure.lang.SeqIterator.
   (concat (map (fn [k] (clojure.lang.MapEntry/create k (.valAt rec k))) fields)
           (iterator-seq ext))))
