# Maps, sets and vectors

A map or set is an `equal` hash table, never mutated in place: every verb builds a fresh one, so persistence holds observably. A map or set prints its entries, and `seq`, `keys` and `vals` walk them, in insertion order. Keys compare by `=`, so a vector, list, map or set key finds an equal one. A sorted map or set (`sorted-map`, `sorted-set`, their `-by` forms) keeps its entries in `compare` or comparator order instead, finds keys by that order, and every map and set verb answers one again; `subseq`/`rsubseq` walk a bounded range of it. Arrays are general:
`(make-array Class dim...)` ignores the class, reads through `aget`, writes through
`aset`, and measures through `alength`; a byte array (`byte-array`, or `make-array` of
`Byte/TYPE`) holds signed bytes. The typed constructors (`object-array`, `to-array`,
`into-array`, `int-array` and friends) coerce their members like the oracle's and answer
the same vectors. A transient (`transient`) edits a copy of a vector, map or set in place until `persistent!` hands it back. A persistent queue (`clojure.lang.PersistentQueue/EMPTY`) adds at the rear and pops from the front.

| Name | Example | Result |
|---|---|---|
| `vector` | `(vector 1 2)` | `[1 2]` |
| `vector?` | `(vector? [1])` | `true` |
| `vec` | `(vec '(1 2))` | `[1 2]` |
| `hash-map` | `(hash-map :a 1)` | `{:a 1}` |
| `array-map` | `(array-map :a 1)` | `{:a 1}` |
| `hash-set` | `(hash-set 1 2 1)` | `#{1 2}` |
| `assoc` | `(assoc {:a 1} :b 2)` | `{:a 1, :b 2}` |
| `dissoc` | `(dissoc {:a 1} :a)` | `{}` |
| `get` | `(get {:a 1} :b :none)` | `:none` |
| `contains?` | `(contains? {:a 1} :a)` | `true` |
| `find` | `(find {:a 1} :a)` | `[:a 1]` |
| `keys` | `(keys (hash-map :a 1))` | `(:a)` |
| `vals` | `(vals (hash-map :a 1))` | `(1)` |
| `key` | `(key (first {:a 1}))` | `:a` |
| `val` | `(val (first {:a 1}))` | `1` |
| `map-entry?` | `(map-entry? (first {:a 1}))` | `true` |
| `merge` | `(merge {:a 1} {:b 2})` | `{:a 1, :b 2}` |
| `conj` | `(conj [1 2] 3)` | `[1 2 3]` |
| `disj` | `(disj #{1 2} 1)` | `#{2}` |
| `set` | `(set [1 2])` | `#{1 2}` |
| `update` | `(update {:a 1} :a inc)` | `{:a 2}` |
| `update-in` | `(update-in {:a {:b 1}} [:a :b] inc)` | `{:a {:b 2}}` |
| `assoc-in` | `(assoc-in {} [:a :b] 1)` | `{:a {:b 1}}` |
| `get-in` | `(get-in {:a {:b 1}} [:a :b])` | `1` |
| `replace` | `(replace {0 :z} [0 1 0])` | `[:z 1 :z]` |
| `select-keys` | `(select-keys {:a 1 :b 2} [:a])` | `{:a 1}` |
| `merge-with` | `(merge-with + {:a 1} {:a 2})` | `{:a 3}` |
| `into` | `(into [] [1 2])` | `[1 2]` |
| `frequencies` | `(frequencies [:a :a])` | `{:a 2}` |
| `peek` | `(peek [1 2 3])` | `3` |
| `pop` | `(pop [1 2 3])` | `[1 2]` |
| `PersistentQueue/EMPTY` | `(seq (conj clojure.lang.PersistentQueue/EMPTY 1 2))` | `(1 2)` |
| `rseq` | `(rseq [1 2 3])` | `(3 2 1)` |
| `subvec` | `(subvec [1 2 3 4] 1 3)` | `[2 3]` |
| `update-keys` | `(update-keys {:a 1} name)` | `{"a" 1}` |
| `update-vals` | `(update-vals {:a 1} inc)` | `{:a 2}` |
| `reduce-kv` | `(reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2})` | `3` |
| `sorted-map` | `(sorted-map :b 1 :a 2)` | `{:a 2, :b 1}` |
| `sorted-map-by` | `(sorted-map-by > 1 :a 2 :b)` | `{2 :b, 1 :a}` |
| `sorted-set` | `(sorted-set 3 1 2)` | `#{1 2 3}` |
| `sorted-set-by` | `(sorted-set-by > 1 3 2)` | `#{3 2 1}` |
| `subseq` | `(subseq (sorted-set 1 2 3) > 1)` | `(2 3)` |
| `rsubseq` | `(rsubseq (sorted-set 1 2 3) < 3)` | `(2 1)` |
| `compare` | `(compare "a" "c")` | `-2` |
| `comparator` | `(sort (comparator >) [1 3 2])` | `(3 2 1)` |
| `vector-of` | `(vector-of :int 1.5 2)` | `[1 2]` |
| `defstruct` | `(do (defstruct s :a) (:a (struct s 1)))` | `1` |
| `struct` | `(do (defstruct s :a) (:a (struct s 1)))` | `1` |
| `struct-map` | `(do (defstruct s :a) (:a (struct-map s :a 1)))` | `1` |
| `make-array` | `(alength (make-array String 2))` | `2` |
| `aget` | `(let [a (make-array String 1)] (aset a 0 "x") (aget a 0))` | `"x"` |
| `aset` | `(let [a (make-array String 1)] (aset a 0 "x"))` | `"x"` |
| `alength` | `(alength (make-array String 2))` | `2` |
| `byte-array` | `(vec (byte-array [1 -1 300]))` | `[1 -1 44]` |
| `bytes` | `(bytes nil)` | `nil` |
| `aclone` | `(vec (aclone (byte-array [1 2])))` | `[1 2]` |
| `amap` | `(vec (amap (int-array [1 2]) i r (* 10 i)))` | `[0 10]` |
| `areduce` | `(let [a (int-array [1 2 3])] (areduce a i r 0 (+ r (aget a i))))` | `6` |
| `aset-byte` | `(aset-byte (byte-array 1) 0 -5)` | `-5` |
| `object-array` | `(vec (object-array [1 nil]))` | `[1 nil]` |
| `to-array` | `(vec (to-array [1 nil]))` | `[1 nil]` |
| `into-array` | `(vec (into-array [1 2]))` | `[1 2]` |
| `int-array` | `(vec (int-array [1.9 300]))` | `[1 300]` |
| `long-array` | `(vec (long-array [1.9]))` | `[1]` |
| `double-array` | `(vec (double-array [1 2]))` | `[1.0 2.0]` |
| `float-array` | `(vec (float-array [1 2]))` | `[1.0 2.0]` |
| `short-array` | `(vec (short-array [100000]))` | `[-31072]` |
| `char-array` | `(vec (char-array [\a \b]))` | `[\a \b]` |
| `boolean-array` | `(vec (boolean-array [true]))` | `[true]` |
| `to-array-2d` | `(mapv vec (to-array-2d [[1 2] [3]]))` | `[[1 2] [3]]` |
| `aset-int` | `(let [a (int-array [1 2])] [(aset-int a 0 9) (vec a)])` | `[9 [9 2]]` |
| `aset-long` | `(let [a (long-array [1 2])] [(aset-long a 0 9) (vec a)])` | `[9 [9 2]]` |
| `aset-double` | `(let [a (double-array [1.0 2.0])] [(aset-double a 0 9) (vec a)])` | `[9 [9.0 2.0]]` |
| `aset-float` | `(let [a (float-array [1.0 2.0])] [(aset-float a 0 9) (vec a)])` | `[9 [9.0 2.0]]` |
| `aset-short` | `(let [a (short-array [1 2])] [(aset-short a 0 9) (vec a)])` | `[9 [9 2]]` |
| `aset-boolean` | `(let [a (boolean-array [true false])] [(aset-boolean a 1 1) (vec a)])` | `[1 [true true]]` |
| `aset-char` | `(let [a (char-array [\a \b])] [(aset-char a 0 66) (mapv int a)])` | `[66 [66 98]]` |
| `ints` | `(vec (ints (int-array [1 2])))` | `[1 2]` |
| `longs` | `(vec (longs (long-array [1 2])))` | `[1 2]` |
| `doubles` | `(vec (doubles (double-array [1.0 2.0])))` | `[1.0 2.0]` |
| `floats` | `(vec (floats (float-array [1.0])))` | `[1.0]` |
| `shorts` | `(vec (shorts (short-array [1])))` | `[1]` |
| `chars` | `(mapv int (chars (char-array [\a])))` | `[97]` |
| `booleans` | `(vec (booleans (boolean-array [true])))` | `[true]` |
| `hash` | `(hash [1 2])` | `156247261` |
| `hash-ordered-coll` | `(hash-ordered-coll '(1 2))` | `156247261` |
| `hash-unordered-coll` | `(hash-unordered-coll [1 2])` | `460223544` |
| `mix-collection-hash` | `(mix-collection-hash 1 0)` | `-2017569654` |
| `hash-combine` | `(hash-combine 0 :a)` | `-626620958` |
| `transient` | `(count (transient [1 2]))` | `2` |
| `persistent!` | `(persistent! (transient [1]))` | `[1]` |
| `conj!` | `(persistent! (conj! (transient [1]) 2))` | `[1 2]` |
| `assoc!` | `(persistent! (assoc! (transient {}) :a 1))` | `{:a 1}` |
| `dissoc!` | `(persistent! (dissoc! (transient {:a 1}) :a))` | `{}` |
| `disj!` | `(persistent! (disj! (transient #{1}) 1))` | `#{}` |
| `pop!` | `(persistent! (pop! (transient [1 2])))` | `[1]` |
