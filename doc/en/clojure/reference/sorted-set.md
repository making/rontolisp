# sorted-set

`(sorted-set x ...)`

`clojure.core/sorted-set`: a set of the given members ordered by `compare`, so it prints
and seqs in order. A member that compares equal to an earlier one is dropped (the
earlier stays). Every set verb takes it and answers a sorted set again (`conj`, `disj`,
`into`, `clojure.set/union` growing it ...); membership is by `compare`, so `(contains?
(sorted-set 1) 1.0)` is `true`. A member `compare` does not order signals the oracle's
`Default comparator requires nil, Number, or Comparable`. It is `=` to a hash set of the
same members. As a value a function of any number of arguments.

```clojure
(println (sorted-set 3 1 2))           ; #{1 2 3}
(println (conj (sorted-set 3 1) 2))    ; #{1 2 3}
(println (first (sorted-set "b" "a"))) ; a
```
