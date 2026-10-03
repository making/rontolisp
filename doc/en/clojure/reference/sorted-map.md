# sorted-map

`(sorted-map k v ...)`

`clojure.core/sorted-map`: a map of the given pairs ordered by `compare`, so it prints,
seqs, and walks `keys`/`vals` in key order. A key that compares equal to an earlier one
replaces its value and keeps the earlier key (`1` and `1.0` are one key). Every map verb
takes it and answers a sorted map again (`assoc`, `dissoc`, `conj`, `merge`, `update`,
`into` ...); a lookup finds a key by `compare`, not `=`. A key `compare` does not order
(a list, a map, a set, a function) signals the oracle's `Default comparator requires nil,
Number, or Comparable`, a key without a value its `No value supplied for key`. It is `=`
to a hash map of the same entries. As a value a function of any number of arguments.

```clojure
(println (sorted-map :b 1 :a 2))       ; {:a 2, :b 1}
(println (assoc (sorted-map :b 1) :a 3)) ; {:a 3, :b 1}
(println (get (sorted-map 1 :one) 1.0)) ; :one
```
