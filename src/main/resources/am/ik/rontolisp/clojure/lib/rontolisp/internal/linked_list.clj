(ns rontolisp.internal.linked-list
  "java.util.LinkedList and java.util.ArrayList where no host is, built into
  rontolisp: the mutable list a (LinkedList.) or (ArrayList.) construction makes
  on wasm, where no host object exists. A deftype over a vector field the
  members replace, so seq, count and a java.util.List member call read it
  through the methods below; the ArrayList is RandomAccess too, so nth reads it
  like the oracle's RT.nthFrom (a LinkedList is not, and nth refuses it there
  as here). The two bodies spell the same members over the functions below.
  Loaded where such a construction first lowers; where the host is the
  construction keeps the host's object. Written for this front end from the
  documented behaviour of the JDK classes of the same names.")

(defn- out-of-bounds
  "The refusal of an index outside the list, in the JDK's words."
  [i size]
  (throw (IndexOutOfBoundsException. ^String (str "Index: " i ", Size: " size))))

(defn- spliced
  "The vector v with the members of coll inserted at index i."
  [v i coll]
  (into (into (subvec v 0 i) coll) (subvec v i)))

(defn- holds?
  "Whether the vector v holds a member = to x."
  [v x]
  (if (some #(= % x) v) true false))

(defn- index-of
  "The index of the first member of v = to x, else -1."
  [v x]
  (loop [i 0]
    (cond
      (= i (count v)) -1
      (= (nth v i) x) i
      :else (recur (inc i)))))

(defn- member-at
  "The member of v at index i, refused outside it."
  [v i]
  (if (and (<= 0 i) (< i (count v)))
    (nth v i)
    (out-of-bounds i (count v))))

(defn- without
  "v without its member at index i, refused outside it."
  [v i]
  (if (and (<= 0 i) (< i (count v)))
    (into (subvec v 0 i) (subvec v (inc i)))
    (out-of-bounds i (count v))))

(defn- inserting
  "v with the members of coll inserted at index i, refused past its end."
  [v i coll]
  (if (and (<= 0 i) (<= i (count v)))
    (spliced v i coll)
    (out-of-bounds i (count v))))

(defn- text
  "The list's toString: [a, b], nil as null."
  [v]
  (str "[" (apply str (interpose ", " (map #(if (nil? %) "null" (str %)) v))) "]"))

(deftype LinkedList [^:unsynchronized-mutable items]
  clojure.lang.Seqable
  (seq [_] (seq items))
  clojure.lang.Counted
  (count [_] (count items))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (seq this)))
  java.util.List
  (size [_] (count items))
  (isEmpty [_] (empty? items))
  (contains [_ x] (holds? items x))
  (indexOf [_ x] (index-of items x))
  (get [_ i] (member-at items i))
  ;; remove of an integer is remove(int), the overload the oracle's reflection
  ;; picks for a long argument
  (remove [_ i] (let [x (member-at items i)] (set! items (without items i)) x))
  (add [_ x] (set! items (conj items x)) true)
  (add [_ i x] (set! items (inserting items i [x])) nil)
  (addAll [_ coll] (let [n (count items)] (set! items (into items coll)) (not= n (count items))))
  (addAll [_ i coll] (let [n (count items)] (set! items (inserting items i coll)) (not= n (count items))))
  (clear [_] (set! items []) nil)
  Object
  (toString [_] (text items)))

(deftype ArrayList [^:unsynchronized-mutable items]
  clojure.lang.Seqable
  (seq [_] (seq items))
  clojure.lang.Counted
  (count [_] (count items))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (seq this)))
  java.util.RandomAccess
  java.util.List
  (size [_] (count items))
  (isEmpty [_] (empty? items))
  (contains [_ x] (holds? items x))
  (indexOf [_ x] (index-of items x))
  (get [_ i] (member-at items i))
  (remove [_ i] (let [x (member-at items i)] (set! items (without items i)) x))
  (add [_ x] (set! items (conj items x)) true)
  (add [_ i x] (set! items (inserting items i [x])) nil)
  (addAll [_ coll] (let [n (count items)] (set! items (into items coll)) (not= n (count items))))
  (addAll [_ i coll] (let [n (count items)] (set! items (inserting items i coll)) (not= n (count items))))
  (clear [_] (set! items []) nil)
  Object
  (toString [_] (text items)))

(defn linked-list
  "An empty list: (LinkedList.)."
  []
  (LinkedList. []))

(defn array-list
  "An empty list: (ArrayList.)."
  []
  (ArrayList. []))
