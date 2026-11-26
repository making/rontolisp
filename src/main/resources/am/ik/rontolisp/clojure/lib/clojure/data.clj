(ns clojure.data
  "Structural comparison of two values, built into rontolisp. Written here
  from the documented behaviour of Clojure's namespace of the same name and
  from the answers it gives: diff sorts what two values hold into three
  parts -- only the first, only the second, both."
  (:require [clojure.set :as set]))

(declare diff)

(defprotocol EqualityPartition
  "How a value takes part in a diff: values of different partitions are
  never compared member by member."
  (equality-partition [x]
    "The partition of x: :atom, :set, :sequential or :map."))

(defprotocol Diff
  "The comparison of two unequal values of one partition."
  (diff-similar [a b]
    "The three parts of a and b, which share a partition."))

(defn- whole-diff
  "Two values compared as wholes: nothing of one is in the other unless
  they are equal."
  [a b]
  (if (= a b) [nil nil a] [a b nil]))

(defn- slot-diff
  "The three parts of one slot k, each a one-entry map or nil. A slot both
  sides hold is compared in depth; it counts as shared when the comparison
  found something in common, or when both hold nil there."
  [a b k]
  (let [va (get a k)
        vb (get b k)
        in-a (contains? a k)
        in-b (contains? b k)
        [only-a only-b common] (diff va vb)
        shared (and in-a in-b (or (some? common) (and (nil? va) (nil? vb))))
        side (fn [present part] (when (and present (or (some? part) (not shared))) {k part}))]
    [(side in-a only-a) (side in-b only-b) (when shared {k common})]))

(defn- slots-diff
  "The three parts of the associative values a and b over the slots ks,
  each part a map of the slots that landed in it, or nil."
  [a b ks]
  (reduce (fn [parts k] (mapv merge parts (slot-diff a b k)))
          [nil nil nil]
          ks))

(defn- map-diff [a b]
  (let [ks (concat (keys a) (clojure.core/remove (fn [k] (contains? a k)) (keys b)))]
    (seq (slots-diff a b ks))))

(defn- as-vector
  "A map of index to value as a vector reaching its highest index, nil in
  every slot the map leaves out; nil for no map."
  [by-index]
  (when (seq by-index)
    (reduce-kv assoc (vec (repeat (inc (apply max (keys by-index))) nil)) by-index)))

(defn- sequential-diff [a b]
  (let [a (vec a)
        b (vec b)]
    (mapv as-vector (slots-diff a b (range (max (count a) (count b)))))))

(defn- set-diff [a b]
  (let [a (set a)
        b (set b)]
    [(not-empty (set/difference a b))
     (not-empty (set/difference b a))
     (not-empty (set/intersection a b))]))

(defn- array? [x]
  (and (some? x) (.isArray (class x))))

(extend-protocol EqualityPartition
  nil
  (equality-partition [x] :atom)
  java.util.Set
  (equality-partition [x] :set)
  java.util.List
  (equality-partition [x] :sequential)
  clojure.lang.IPersistentVector
  (equality-partition [x] :sequential)
  java.util.Map
  (equality-partition [x] :map)
  Object
  (equality-partition [x]
    (cond (map? x) :map
          (array? x) :sequential
          :else :atom)))

(extend-protocol Diff
  nil
  (diff-similar [a b] (whole-diff a b))
  java.util.Set
  (diff-similar [a b] (set-diff a b))
  java.util.List
  (diff-similar [a b] (sequential-diff a b))
  clojure.lang.IPersistentVector
  (diff-similar [a b] (sequential-diff a b))
  java.util.Map
  (diff-similar [a b] (map-diff a b))
  Object
  (diff-similar [a b]
    (cond (map? a) (map-diff a b)
          (array? a) (sequential-diff a b)
          :else (whole-diff a b))))

(defn diff
  "Compares a and b in depth and answers [only-in-a only-in-b in-both].
  Equal values are all in-both. Maps are compared key by key, down into the
  values of the keys they share. Sequential collections are compared index
  by index, each part a vector with nil where it holds nothing. Sets are
  split into members, never compared inside. Anything else, strings
  included, is one indivisible value."
  [a b]
  (cond (= a b) [nil nil a]
        (= (equality-partition a) (equality-partition b)) (diff-similar a b)
        :else [a b nil]))
