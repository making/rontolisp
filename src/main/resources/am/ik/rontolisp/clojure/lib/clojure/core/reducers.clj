(ns clojure.core.reducers
  "Reducers and folders, built into rontolisp. A reducer is a reducible view
  of a collection that transforms the reducing function a reduction hands it,
  so nothing is realized between the steps; a folder is also foldable: fold
  reduces a vector in halves down to n members (512 by default), each part from
  (combinef), and combines the parts' results with combinef, in order. There is
  no fork/join pool here: the parts reduce one after the other. Written for
  this front end from the documented behaviour of Clojure's namespace of the
  same name."
  (:refer-clojure :exclude [reduce map mapcat filter remove take take-while drop flatten cat])
  (:require [clojure.core.protocols :as p]
            [rontolisp.internal.reducers :as kernel]))

(defn reduce
  "Like clojure.core/reduce, but (f) is the init when none is given, and a map
  or record reduces through kv-reduce, as (f acc k v)."
  ([f coll] (reduce f (f) coll))
  ([f init coll]
   (if (instance? java.util.Map coll)
     (p/kv-reduce coll f init)
     (p/coll-reduce coll f init))))

(defprotocol CollFold
  "Folding: a collection reduced part by part, the parts' results combined."
  (coll-fold [coll n combinef reducef]))

(defn fold
  "coll reduced with reducef in parts of about n members (512 by default), each
  part from (combinef), the parts' results combined with combinef (reducef when
  none is given). combinef must be associative, and (combinef) its identity. A
  map reduces through kv-reduce, as (reducef acc k v)."
  ([reducef coll] (fold reducef reducef coll))
  ([combinef reducef coll] (fold 512 combinef reducef coll))
  ([n combinef reducef coll] (coll-fold coll n combinef reducef)))

(defn reducer
  "coll as a reducible collection whose reductions hand their reducing function
  through xf, a function from a reducing function to a reducing function."
  [coll xf]
  (reify
    p/CollReduce
    (coll-reduce [this f] (p/coll-reduce this f (f)))
    (coll-reduce [_ f init] (p/coll-reduce coll (xf f) init))))

(defn folder
  "coll as a reducible and foldable collection whose reductions and folds hand
  their reducing function through xf."
  [coll xf]
  (reify
    p/CollReduce
    (coll-reduce [_ f] (p/coll-reduce coll (xf f) (f)))
    (coll-reduce [_ f init] (p/coll-reduce coll (xf f) init))
    CollFold
    (coll-fold [_ n combinef reducef] (coll-fold coll n combinef (xf reducef)))))

(defn map
  "Applies f to every value in the reduction of coll (a map's entries as
  (f k v)). Foldable."
  ([f] (fn [coll] (map f coll)))
  ([f coll]
   (folder coll
           (fn [f1]
             (fn
               ([] (f1))
               ([ret v] (f1 ret (f v)))
               ([ret k v] (f1 ret (f k v))))))))

(defn mapcat
  "Applies f to every value in the reduction of coll and steps the members of
  each answer in turn. Foldable."
  ([f] (fn [coll] (mapcat f coll)))
  ([f coll]
   (folder coll
           (fn [f1]
             (let [step (fn
                          ([ret v]
                           (let [x (f1 ret v)] (if (reduced? x) (reduced x) x)))
                          ([ret k v]
                           (let [x (f1 ret k v)] (if (reduced? x) (reduced x) x))))]
               (fn
                 ([] (f1))
                 ([ret v] (reduce step ret (f v)))
                 ([ret k v] (reduce step ret (f k v)))))))))

(defn filter
  "Keeps the values in the reduction of coll for which (pred val) is logical
  true. Foldable."
  ([pred] (fn [coll] (filter pred coll)))
  ([pred coll]
   (folder coll
           (fn [f1]
             (fn
               ([] (f1))
               ([ret v] (if (pred v) (f1 ret v) ret))
               ([ret k v] (if (pred k v) (f1 ret k v) ret)))))))

(defn remove
  "Drops the values in the reduction of coll for which (pred val) is logical
  true. Foldable."
  ([pred] (fn [coll] (remove pred coll)))
  ([pred coll] (filter (complement pred) coll)))

(defn flatten
  "The members of any nesting of sequential collections in coll, as one
  foldable collection."
  ([] (fn [coll] (flatten coll)))
  ([coll]
   (folder coll
           (fn [f1]
             (fn
               ([] (f1))
               ([ret v]
                (if (sequential? v)
                  (p/coll-reduce (flatten v) f1 ret)
                  (f1 ret v))))))))

(defn take-while
  "Ends the reduction of coll at the first value for which (pred val) is
  logical false."
  ([pred] (fn [coll] (take-while pred coll)))
  ([pred coll]
   (reducer coll
            (fn [f1]
              (fn
                ([] (f1))
                ([ret v] (if (pred v) (f1 ret v) (reduced ret)))
                ([ret k v] (if (pred k v) (f1 ret k v) (reduced ret))))))))

(defn take
  "Ends the reduction of coll after n values."
  ([n] (fn [coll] (take n coll)))
  ([n coll]
   (reducer coll
            (fn [f1]
              (let [left (volatile! n)]
                (fn
                  ([] (f1))
                  ([ret v]
                   (vswap! left dec)
                   (if (neg? @left) (reduced ret) (f1 ret v)))
                  ([ret k v]
                   (vswap! left dec)
                   (if (neg? @left) (reduced ret) (f1 ret k v)))))))))

(defn drop
  "Leaves the first n values out of the reduction of coll."
  ([n] (fn [coll] (drop n coll)))
  ([n coll]
   (reducer coll
            (fn [f1]
              (let [left (volatile! n)]
                (fn
                  ([] (f1))
                  ([ret v]
                   (vswap! left dec)
                   (if (neg? @left) (f1 ret v) ret))
                  ([ret k v]
                   (vswap! left dec)
                   (if (neg? @left) (f1 ret k v) ret))))))))

(defn cat
  "The combining function of foldcat: of no arguments a fresh empty
  accumulator, of two collections one holding both in order (either alone when
  the other is empty), of a constructor a combining function whose identity
  is (ctor)."
  ([] (kernel/accumulator))
  ([ctor]
   (fn
     ([] (ctor))
     ([left right] (cat left right))))
  ([left right]
   (cond
     (zero? (count left)) right
     (zero? (count right)) left
     :else (kernel/joined left right))))

(defn append!
  "Adds x to acc, an accumulator cat made, and answers acc."
  [acc x]
  (kernel/append acc x))

(defn foldcat
  "(fold cat append! coll): the members of coll's reduction, in order, in one
  accumulator."
  [coll]
  (fold cat append! coll))

(defn monoid
  "A combining function of op whose identity, the call of no arguments, is
  (ctor)."
  [op ctor]
  (fn
    ([] (ctor))
    ([a b] (op a b))))

(defn- fold-range
  "The members start (inclusive) to end of the vector v folded: halves down to
  n members, each reduced from (combinef), the halves' results combined left
  to right."
  [v start end n combinef reducef]
  (let [size (- end start)]
    (cond
      (zero? size) (combinef)
      (<= size n) (reduce reducef (combinef) (subvec v start end))
      :else (let [middle (+ start (quot size 2))]
              (combinef (fold-range v start middle n combinef reducef)
                        (fold-range v middle end n combinef reducef))))))

(extend-protocol CollFold
  nil
  (coll-fold [_ _ combinef _] (combinef))
  Object
  (coll-fold [coll _ combinef reducef] (reduce reducef (combinef) coll))
  clojure.lang.IPersistentVector
  (coll-fold [v n combinef reducef]
    (if (kernel/accumulator? v)
      (reduce reducef (combinef) v)
      (fold-range v 0 (count v) n combinef reducef))))
