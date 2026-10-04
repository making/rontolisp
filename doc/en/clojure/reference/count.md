# count

`(count coll)`

Answers the number of elements of `coll`. Table-aware: maps and sets answer their
`hash-table-count` directly (no seq built), everything else the length of the seq view
(a lazy seq realizes whole first). A Java `Collection` or `Map` answers its `size`, a
`CharSequence` its `length`; any other Java `Iterable` signals, like the oracle.

```clojure
(println (count '(1 2 3)))   ; 3
(println (count [1 2 3]))    ; 3
(println (count {:a 1 :b 2})) ; 2
(println (count #{1 2 3}))   ; 3
(println (count false))      ; 0
```
