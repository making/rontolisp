# nthrest

`(nthrest coll n)`

Answers `coll` itself when `n` is not positive, else the rest past its first `n`
members -- `nil` once it runs out, where the oracle prints `()`. As a value a
two-argument function.

```clojure
(prn (nthrest [1 2 3] 0)) ; [1 2 3]
(prn (nthrest [1 2 3] 1)) ; (2 3)
```
