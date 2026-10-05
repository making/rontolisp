# seq

`(seq coll)`

Answers the seq view of `coll`: a lazy wrapper realizes one level (never past it, so
infinite seqs stay infinite); a list passes through untouched, a vector or string
coerces element by element, a map contributes one two-vector per entry and a set one
member per element, both in the table's walk order (unspecified). `nil` is empty. A Java `Iterable` contributes its elements, a Java `Map` one two-vector per
entry and a `CharSequence` its characters, read whole when the seq is taken (interpreter
and JVM). Anything else (a keyword, `false`, a number) signals
`IllegalArgumentException`, like the oracle.

The empty result of every seq verb is `nil`. There is no chunking: a lazy seq realizes
one element at a time.

```clojure
(println (seq '(1 2)))       ; (1 2)
(println (seq [1 2]))        ; (1 2)
(println (seq nil))          ; nil
(println (first {:a 1}))     ; [:a 1]
(println (count (seq "ab"))) ; 2
```
