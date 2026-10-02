# nthnext

`(nthnext coll n)`

Answers `coll`'s seq past its first `n` members, `nil` when nothing is left. As a
value a two-argument function.

```clojure
(println (nthnext [1 2 3] 1)) ; (2 3)
(println (nthnext [1 2 3] 3)) ; nil
```
