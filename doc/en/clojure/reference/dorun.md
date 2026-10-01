# dorun

`(dorun coll)` / `(dorun n coll)`

Realizes a collection for effect and answers `nil`. Strict seqs are already lists, so
realizing one is evaluating it; a lazy seq is evaluated but not forced -- take it first.
The optional count only evaluates. Also names a function value.

```clojure
(println (dorun [1 2 3])) ; nil
```
