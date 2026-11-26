(ns clojure.core.reducers
  "Reducers, built into rontolisp: collections that are not built until
  something reduces them. Written here from the documented behaviour of
  Clojure's namespace of the same name and from the answers it gives.

  A reducer wraps a collection and a way to rewrite a reducing function; a
  reduction of the reducer is a reduction of the collection with the
  rewritten function, so a chain of map, filter and the like makes no
  collection in between. A folder is a reducer that fold may also take
  apart. fold halves a vector until a piece holds no more than n members
  (512 unless given), reduces each piece starting from (combinef) and joins
  the results, in order, with combinef. There are no worker threads here:
  the pieces are reduced one after the other, left to right."
  (:refer-clojure :exclude [reduce map mapcat filter remove take take-while drop flatten cat])
  (:require [clojure.core.protocols :as p]
            [rontolisp.internal.reducers :as kernel]))

(defn reduce
  "Like clojure.core/reduce, but a map is reduced by key and value, f taking
  the result, a key and a value, and without init the reduction starts from
  (f)."
  ([f coll] (reduce f (f) coll))
  ([f init coll]
   (if (instance? java.util.Map coll)
     (p/kv-reduce coll f init)
     (p/coll-reduce coll f init))))

(defprotocol CollFold
  "A collection that fold knows how to take apart."
  (coll-fold [coll n combinef reducef]
    "Folds coll: pieces of at most n members reduced with reducef from
    (combinef), their results joined with combinef."))

(defn fold
  "Reduces coll in pieces and joins the results. Each piece of about n
  members (512 unless given) is reduced with reducef, starting from
  (combinef); the pieces' results are then joined in order with combinef,
  which must be associative and answer its identity when called with no
  argument. With reducef alone, it is the combinef too. Only a vector, or a
  folder over one, is taken apart; anything else is one piece."
  ([reducef coll] (fold reducef reducef coll))
  ([combinef reducef coll] (fold 512 combinef reducef coll))
  ([n combinef reducef coll] (coll-fold coll n combinef reducef)))

(defn reducer
  "A reducible view of coll in which every reducing function goes through
  xf first: xf takes a reducing function and answers the one to use."
  [coll xf]
  (reify
    p/CollReduce
    (coll-reduce [this f] (p/coll-reduce this f (f)))
    (coll-reduce [_ f init] (p/coll-reduce coll (xf f) init))))

(defn folder
  "A reducible and foldable view of coll in which every reducing function
  goes through xf first: xf takes a reducing function and answers the one to
  use."
  [coll xf]
  (reify
    p/CollReduce
    (coll-reduce [this f] (p/coll-reduce this f (f)))
    (coll-reduce [_ f init] (p/coll-reduce coll (xf f) init))
    CollFold
    (coll-fold [_ n combinef reducef] (coll-fold coll n combinef (xf reducef)))))

;; Every operation below rewrites a reducing function rf into one of three
;; arities: none (the start value, rf's own), a result and a member, and a
;; result, a key and a value (what a map hands a folder).

(defn map
  "A foldable view of coll with f applied to each member. With f alone, a
  function of a collection."
  ([f] (fn [coll] (map f coll)))
  ([f coll]
   (folder coll
           (fn [rf]
             (fn
               ([] (rf))
               ([acc x] (rf acc (f x)))
               ([acc k v] (rf acc (f k v))))))))

(defn mapcat
  "A foldable view of coll with f applied to each member and the members of
  each result in its place: f answers a collection. With f alone, a function
  of a collection."
  ([f] (fn [coll] (mapcat f coll)))
  ([f coll]
   (folder coll
           (fn [rf]
             ;; A stop inside one result must stop the whole reduction: wrap it
             ;; once more, so the inner reduce hands it on still wrapped.
             (let [inner (fn [acc x]
                           (let [step (rf acc x)]
                             (if (reduced? step) (reduced step) step)))]
               (fn
                 ([] (rf))
                 ([acc x] (reduce inner acc (f x)))
                 ([acc k v] (reduce inner acc (f k v)))))))))

(defn filter
  "A foldable view of the members of coll for which pred is true. With pred
  alone, a function of a collection."
  ([pred] (fn [coll] (filter pred coll)))
  ([pred coll]
   (folder coll
           (fn [rf]
             (fn
               ([] (rf))
               ([acc x] (if (pred x) (rf acc x) acc))
               ([acc k v] (if (pred k v) (rf acc k v) acc)))))))

(defn remove
  "A foldable view of the members of coll for which pred is false. With
  pred alone, a function of a collection."
  ([pred] (fn [coll] (remove pred coll)))
  ([pred coll] (filter (complement pred) coll)))

(defn flatten
  "A foldable view of coll with every sequential member, however deep,
  replaced by its members. With no argument, a function of a collection."
  ([] (fn [coll] (flatten coll)))
  ([coll]
   (folder coll
           (fn [rf]
             (fn
               ([] (rf))
               ([acc x]
                (if (sequential? x)
                  (p/coll-reduce (flatten x) rf acc)
                  (rf acc x))))))))

(defn take-while
  "A reducible view of the members of coll up to the first for which pred
  is false. With pred alone, a function of a collection."
  ([pred] (fn [coll] (take-while pred coll)))
  ([pred coll]
   (reducer coll
            (fn [rf]
              (fn
                ([] (rf))
                ([acc x] (if (pred x) (rf acc x) (reduced acc)))
                ([acc k v] (if (pred k v) (rf acc k v) (reduced acc))))))))

(defn take
  "A reducible view of the first n members of coll. With n alone, a function
  of a collection."
  ([n] (fn [coll] (take n coll)))
  ([n coll]
   (reducer coll
            (fn [rf]
              ;; Counted per reduction: the member after the n-th ends it.
              (let [left (atom n)]
                (fn
                  ([] (rf))
                  ([acc x] (if (neg? (swap! left dec)) (reduced acc) (rf acc x)))
                  ([acc k v] (if (neg? (swap! left dec)) (reduced acc) (rf acc k v)))))))))

(defn drop
  "A reducible view of coll without its first n members. With n alone, a
  function of a collection."
  ([n] (fn [coll] (drop n coll)))
  ([n coll]
   (reducer coll
            (fn [rf]
              (let [left (atom n)]
                (fn
                  ([] (rf))
                  ([acc x] (if (neg? (swap! left dec)) (rf acc x) acc))
                  ([acc k v] (if (neg? (swap! left dec)) (rf acc k v) acc))))))))

(defn cat
  "A combinef that joins collections end to end. (cat) answers an empty
  accumulator that append! adds to; (cat left right) answers the one that is
  not empty, or one accumulator holding the members of both. (cat ctor)
  answers a combinef whose empty collection is (ctor)."
  ([] (kernel/accumulator))
  ([ctor]
   (fn
     ([] (ctor))
     ([left right] (cat left right))))
  ([left right]
   (cond (zero? (count left)) right
         (zero? (count right)) left
         :else (kernel/joined left right))))

(defn append!
  "Adds x at the end of the accumulator acc and answers acc."
  [acc x]
  (when-not (kernel/accumulator? acc)
    (throw (UnsupportedOperationException.)))
  (kernel/append acc x))

(defn foldcat
  "The members of coll gathered by fold: (fold cat append! coll)."
  [coll]
  (fold cat append! coll))

(defn monoid
  "A combinef made of the associative function op and of ctor, which
  answers op's identity when called with no argument."
  [op ctor]
  (fn
    ([] (ctor))
    ([a b] (op a b))))

(defn- fold-vector
  "Folds the members of v from index from up to index to."
  [v from to n combinef reducef]
  (let [size (- to from)]
    (if (or (<= size n) (= size 1))
      (clojure.core/reduce reducef (combinef) (subvec v from to))
      (let [middle (+ from (quot size 2))]
        (combinef (fold-vector v from middle n combinef reducef)
                  (fold-vector v middle to n combinef reducef))))))

(extend-protocol CollFold
  nil
  (coll-fold [coll n combinef reducef] (combinef))

  Object
  (coll-fold [coll n combinef reducef] (reduce reducef (combinef) coll))

  clojure.lang.IPersistentVector
  (coll-fold [v n combinef reducef]
    (cond (kernel/accumulator? v) (reduce reducef (combinef) v)
          (empty? v) (combinef)
          :else (fold-vector v 0 (count v) n combinef reducef))))
