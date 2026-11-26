# sorted-set-by

`(sorted-set-by comparator x ...)`

`clojure.core/sorted-set-by`: a sorted set ordered by `comparator`, read like
`sorted-map-by`'s (a number by its integer part's sign, a boolean as less-than).
Members it calls equal are one member, the first one kept. Every verb keeps the
comparator. As a value a function of a comparator and members.

```clojure
(println (sorted-set-by > 1 3 2))                                ; #{3 2 1}
(prn (sorted-set-by #(compare (count %1) (count %2)) "ab" "c" "de")) ; #{"c" "ab"}
```
