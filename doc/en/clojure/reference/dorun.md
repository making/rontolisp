# dorun

`(dorun coll)` / `(dorun n coll)`

Realizes a collection for effect and answers `nil`. Seqs are already strict lists here,
so realizing one is evaluating it; the optional count only evaluates. Also names a
function value.

```clojure
(println (dorun [1 2 3])) ; nil
```
