(ns clojure.data
  "Recursive comparison of two values, built into rontolisp: diff answers what
  only the first holds, what only the second holds and what both hold. Written
  for this front end from the documented behaviour of Clojure's namespace of the
  same name."
  (:require [clojure.set :as set]))

(declare diff)

(defprotocol EqualityPartition
  "The kind of value diff compares a value as."
  (equality-partition [x]
    "Which of :atom, :set, :sequential and :map x is compared as."))

(defprotocol Diff
  "How diff compares two values of one equality partition."
  (diff-similar [a b]
    "The diff of a and b, two values of one equality partition."))

(defn- atom-diff
  "a and b compared as wholes."
  [a b]
  (if (= a b) [nil nil a] [a b nil]))

(defn- key-diff
  "The single-entry maps (or nils) the key k adds to the diff of the
  associative a and b: what only a holds there, what only b holds and what both
  hold."
  [a b k]
  (let [va (get a k)
        vb (get b k)
        [only-a only-b both] (diff va vb)
        in-a (contains? a k)
        in-b (contains? b k)
        same (and in-a in-b (or (some? both) (and (nil? va) (nil? vb))))]
    [(when (and in-a (or (some? only-a) (not same))) {k only-a})
     (when (and in-b (or (some? only-b) (not same))) {k only-b})
     (when same {k both})]))

(defn- associative-diff
  "The diff of the associative a and b over the keys ks, as three maps (or
  nils) merged key by key."
  [a b ks]
  (reduce (fn [acc part] (doall (map merge acc part)))
          [nil nil nil]
          (map (fn [k] (key-diff a b k)) ks)))

(defn- vectorize
  "The map m of indexes to values as a vector holding each value at its
  index, nil between; nil for an empty m."
  [m]
  (when (seq m)
    (reduce (fn [v [i x]] (assoc v i x))
            (vec (repeat (apply max (keys m)) nil))
            m)))

(defn- sequential-diff
  "a and b compared index by index, each part a vector."
  [a b]
  (vec (map vectorize (associative-diff (vec a) (vec b) (range (max (count a) (count b)))))))

(defn- map-diff
  "a and b compared key by key, over the union of their key seqs (a key both
  hold comes twice, which merges alike)."
  [a b]
  (associative-diff a b (set/union (keys a) (keys b))))

(defn- set-diff
  "a and b compared member by member."
  [a b]
  [(not-empty (set/difference a b))
   (not-empty (set/difference b a))
   (not-empty (set/intersection a b))])

(extend-protocol EqualityPartition
  nil
  (equality-partition [_] :atom)
  java.util.Set
  (equality-partition [_] :set)
  java.util.List
  (equality-partition [_] :sequential)
  clojure.lang.IPersistentVector
  (equality-partition [_] :sequential)
  java.util.Map
  (equality-partition [_] :map)
  Object
  (equality-partition [x] (if (map? x) :map :atom)))

(extend-protocol Diff
  nil
  (diff-similar [a b] (atom-diff a b))
  java.util.Set
  (diff-similar [a b] (set-diff a b))
  java.util.List
  (diff-similar [a b] (sequential-diff a b))
  clojure.lang.IPersistentVector
  (diff-similar [a b] (sequential-diff a b))
  java.util.Map
  (diff-similar [a b] (map-diff a b))
  Object
  (diff-similar [a b] (if (map? a) (map-diff a b) (atom-diff a b))))

(defn diff
  "Compares a and b recursively, answering [only-in-a only-in-b in-both]. Equal
  values answer [nil nil a]. Two maps compare key by key and two sequential
  collections index by index (their parts as vectors), each shared position
  compared recursively; two sets compare member by member; anything else,
  strings included, compares as a whole, as do two values of different kinds."
  [a b]
  (if (= a b)
    [nil nil a]
    (if (= (equality-partition a) (equality-partition b))
      (diff-similar a b)
      (atom-diff a b))))
