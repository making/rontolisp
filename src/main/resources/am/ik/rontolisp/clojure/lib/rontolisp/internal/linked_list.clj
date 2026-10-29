(ns rontolisp.internal.linked-list
  "java.util.LinkedList where no host is, built into rontolisp: the mutable list
  a (LinkedList.) construction makes on wasm, where no host object exists. A deftype over a vector field the members replace, so
  seq, count and a java.util.List member call read it through the methods
  below. Loaded where such a construction first lowers; where the host is the
  construction keeps the host's object. Written for this front end from the
  documented behaviour of the JDK class of the same name.")

(defn- out-of-bounds
  "The refusal of an index outside the list, in the JDK's words."
  [i size]
  (throw (IndexOutOfBoundsException. ^String (str "Index: " i ", Size: " size))))

(defn- spliced
  "The vector v with the members of coll inserted at index i."
  [v i coll]
  (into (into (subvec v 0 i) coll) (subvec v i)))

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
  (contains [_ x] (if (some #(= % x) items) true false))
  (indexOf [_ x]
    (loop [i 0]
      (cond
        (= i (count items)) -1
        (= (nth items i) x) i
        :else (recur (inc i)))))
  (get [_ i]
    (if (and (<= 0 i) (< i (count items)))
      (nth items i)
      (out-of-bounds i (count items))))
  ;; remove of an integer is remove(int), the overload the oracle's reflection
  ;; picks for a long argument
  (remove [_ i]
    (if (and (<= 0 i) (< i (count items)))
      (let [x (nth items i)]
        (set! items (into (subvec items 0 i) (subvec items (inc i))))
        x)
      (out-of-bounds i (count items))))
  (add [_ x]
    (set! items (conj items x))
    true)
  (add [_ i x]
    (if (and (<= 0 i) (<= i (count items)))
      (do (set! items (spliced items i [x])) nil)
      (out-of-bounds i (count items))))
  (addAll [_ coll]
    (let [n (count items)]
      (set! items (into items coll))
      (not= n (count items))))
  (addAll [_ i coll]
    (if (and (<= 0 i) (<= i (count items)))
      (let [n (count items)]
        (set! items (spliced items i coll))
        (not= n (count items)))
      (out-of-bounds i (count items))))
  (clear [_]
    (set! items [])
    nil)
  Object
  (toString [_]
    (str "[" (apply str (interpose ", " (map #(if (nil? %) "null" (str %)) items))) "]")))

(defn linked-list
  "An empty list: (LinkedList.)."
  []
  (LinkedList. []))
