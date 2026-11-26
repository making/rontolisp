# chunked-seq?

`(chunked-seq? x)`

`clojure.core/chunked-seq?`: `false` for every value: seqs realize one element at a time here, so none is chunked (the oracle answers `true` for `(seq [1 2])` and a `range`). The argument is still evaluated. As a value a one-argument function.

```clojure
(println (chunked-seq? (seq [1 2])))  ; false
```
