# Seqs

A seq is a list view over any collection: strict collections coerce (lists pass through
untouched, vectors and strings coerce, maps contribute one two-vector per entry and sets
one member per element, in the table's walk order, unspecified), while a lazy seq
realizes one element at a time through the same view. A verb that walks the whole
collection realizes a lazy input first; one that stops early (`second`, `nth`, `some`,
`take-while`, ...) steps through it, so an infinite input answers. The empty result of every verb is
`nil`. There is no chunking. The one-argument arities of the seq verbs are
[transducers](transducers.md).

| Name | Example | Result |
|---|---|---|
| `seq` | `(seq [1 2 3])` | `(1 2 3)` |
| `first` | `(first {:a 1})` | `[:a 1]` |
| `rest` | `(rest '(1 2 3))` | `(2 3)` |
| `next` | `(next [1])` | `nil` |
| `cons` | `(cons 1 [2 3])` | `(1 2 3)` |
| `list*` | `(list* 1 2 [3 4])` | `(1 2 3 4)` |
| `count` | `(count {:a 1 :b 2})` | `2` |
| `empty?` | `(empty? "")` | `true` |
| `map` | `(map inc [1 2 3])` | `(2 3 4)` |
| `filter` | `(filter odd? [1 2 3 4])` | `(1 3)` |
| `mapv` | `(mapv inc [1 2 3])` | `[2 3 4]` |
| `filterv` | `(filterv odd? [1 2 3 4])` | `[1 3]` |
| `reduce` | `(reduce + 0 [1 2 3])` | `6` |
| `apply` | `(apply max 1 [2 3])` | `3` |
| `concat` | `(concat [1] '(2) #{3})` | `(1 2 3)` |
| `mapcat` | `(mapcat reverse [[1 2] [3 4]])` | `(2 1 4 3)` |
| `take` | `(take 2 [1 2 3])` | `(1 2)` |
| `drop` | `(drop 2 [1 2 3])` | `(3)` |
| `lazy-seq` | `(take 2 (lazy-seq (cons 1 nil)))` | `(1)` |
| `lazy-cat` | `(take 3 (lazy-cat [0] [1 2]))` | `(0 1 2)` |
| `repeat` | `(take 2 (repeat :x))` | `(:x :x)` |
| `cycle` | `(take 3 (cycle [1 2]))` | `(1 2 1)` |
| `iterate` | `(take 2 (iterate inc 0))` | `(0 1)` |
| `iterator-seq` | `(iterator-seq (.iterator [1 2]))` | `(1 2)` |
| `repeatedly` | `(take 2 (repeatedly (fn [] 7)))` | `(7 7)` |
| `range` | `(range 0 6 2)` | `(0 2 4)` |
| `nth` | `(nth [1 2 3] 5 :none)` | `:none` |
| `rand-nth` | `(rand-nth [:a])` | `:a` |
| `shuffle` | `(shuffle [])` | `[]` |
| `keep` | `(keep inc [1 2 3])` | `(2 3 4)` |
| `keep-indexed` | `(keep-indexed (fn [i x] (when (odd? x) i)) [10 11 12])` | `(1)` |
| `map-indexed` | `(map-indexed vector [:a :b])` | `([0 :a] [1 :b])` |
| `every?` | `(every? odd? [1 3])` | `true` |
| `some` | `(some even? [1 3])` | `nil` |
| `remove` | `(remove odd? [1 2 3 4])` | `(2 4)` |
| `distinct` | `(distinct [3 1 3 2 1])` | `(3 1 2)` |
| `partition` | `(partition 2 1 [1 2 3])` | `((1 2) (2 3))` |
| `take-while` | `(take-while pos? [3 1 -1 5])` | `(3 1)` |
| `drop-while` | `(drop-while neg? [-2 -1 0 1])` | `(0 1)` |
| `interleave` | `(interleave [1 2] [:a :b])` | `(1 :a 2 :b)` |
| `interpose` | `(interpose 0 [1 2 3])` | `(1 0 2 0 3)` |
| `zipmap` | `(zipmap [:a] [1 2])` | `{:a 1}` |
| `group-by` | `(group-by odd? [1 3])` | `{true [1 3]}` |
| `sort` | `(sort [3 1 2])` | `(1 2 3)` |
| `sort-by` | `(sort-by count ["aaa" "b" "cc"])` | `(b cc aaa)` |
| `last` | `(last [1 2 3])` | `3` |
| `butlast` | `(butlast [1 2 3])` | `(1 2)` |
| `second` | `(second [1 2 3])` | `2` |
| `ffirst` | `(ffirst [[1 2]])` | `1` |
| `nfirst` | `(nfirst [[1 2 3]])` | `(2 3)` |
| `fnext` | `(fnext [1 2 3])` | `2` |
| `nnext` | `(nnext [1 2 3])` | `(3)` |
| `drop-last` | `(drop-last [1 2 3])` | `(1 2)` |
| `split-at` | `(split-at 2 [1 2 3 4])` | `[(1 2) (3 4)]` |
| `split-with` | `(split-with odd? [1 3 4 5])` | `[(1 3) (4 5)]` |
| `take-last` | `(take-last 2 [1 2 3])` | `(2 3)` |
| `nthnext` | `(nthnext [1 2 3] 1)` | `(2 3)` |
| `nthrest` | `(nthrest [1 2 3] 0)` | `[1 2 3]` |
| `dedupe` | `(dedupe [1 1 2 1])` | `(1 2 1)` |
| `partition-all` | `(partition-all 2 [1 2 3])` | `((1 2) (3))` |
| `partition-by` | `(partition-by odd? [1 3 2])` | `((1 3) (2))` |
| `take-nth` | `(take-nth 2 [1 2 3])` | `(1 3)` |
| `not-empty` | `(not-empty [])` | `nil` |
| `empty` | `(empty [1 2])` | `[]` |
| `pmap` | `(pmap inc [1 2])` | `(2 3)` |
